package com.alibaba.mnn.english;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;

/** Run the production downloader against the published fixed ModelScope revisions, on a host JVM. */
public final class ModelDownloadSmoke {
    public static void main(String[] args) throws Exception {
        File root = new File(args[1]); boolean resumed = false; int files = 0; long bytes = 0;
        for (String line : Files.readAllLines(new File(args[0]).toPath(), StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t");
            File target = new File(root, fields[0]); long size = Long.parseLong(fields[2]);
            if (!resumed && size > 1000000) {
                ResumableDownloader interrupted = new ResumableDownloader();
                try {
                    interrupted.download(fields[1], target, size, fields[3], done -> { if (done >= 131072) interrupted.cancel(); });
                    throw new AssertionError("Expected cancellation");
                } catch (CancellationException expected) {}
                long saved = new File(target.getPath() + ".part").length();
                if (saved < 131072 || saved >= size || target.exists()) throw new AssertionError("Partial download not retained");
                System.out.println("Paused actual ModelScope transfer at " + saved + " bytes; resuming with a new downloader");
                resumed = true;
            }
            new ResumableDownloader().download(fields[1], target, size, fields[3], done -> {});
            if (!ResumableDownloader.verified(target, size, fields[3]) || new File(target.getPath() + ".part").exists()) throw new AssertionError("Verification failed");
            files++; bytes += size;
            System.out.println("VERIFIED " + fields[0] + " " + size);
            // Calling again must succeed without network, covered explicitly by local HTTP unit tests too.
            new ResumableDownloader().download(fields[1], target, size, fields[3], done -> {});
        }
        System.out.println("PASS " + files + " files, " + bytes + " bytes; interruption/resume=" + resumed);
    }
}
