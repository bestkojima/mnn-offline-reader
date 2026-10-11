package com.alibaba.mnn.english;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real HTTP responses exercise resume boundaries; no inference or Android stub methods are involved. */
public class ResumableDownloaderTest {
    private ServerSocket server;
    private Thread serverThread;
    private File directory, target;
    private byte[] payload;
    private String hash, url;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> range = new AtomicReference<>();
    private volatile String mode = "normal";

    @Before public void setup() throws Exception {
        directory = Files.createTempDirectory("reader-download-test").toFile(); target = new File(directory, "tts.mnn");
        payload = new byte[300123]; for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 37);
        File fixture = new File(directory, "fixture"); Files.write(fixture.toPath(), payload); hash = ResumableDownloader.sha256(fixture);
        server = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        serverThread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line, header = null;
                    while ((line = input.readLine()) != null && !line.isEmpty()) {
                        if (line.startsWith("Range: ")) header = line.substring(7);
                    }
                    requests.incrementAndGet(); range.set(header);
                    String responseMode = mode;
                    int offset = header == null || "ignore-range".equals(responseMode) ? 0 : Integer.parseInt(header.substring(6, header.length() - 1));
                    OutputStream output = socket.getOutputStream();
                    if ("error".equals(responseMode)) {
                        output.write("HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        continue;
                    }
                    String headers = "HTTP/1.1 " + (offset > 0 ? "206 Partial Content" : "200 OK")
                            + "\r\nContent-Length: " + (payload.length - offset) + "\r\nConnection: close\r\n";
                    if ("invalid-range".equals(responseMode)) headers += "Content-Range: bytes 1-2/3\r\n";
                    else if (offset > 0) headers += "Content-Range: bytes " + offset + "-" + (payload.length - 1) + "/" + payload.length + "\r\n";
                    if ("compressed".equals(responseMode)) headers += "Content-Encoding: gzip\r\n";
                    output.write((headers + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    byte[] body = "corrupt".equals(responseMode) ? new byte[payload.length] : payload;
                    output.write(body, offset, body.length - offset);
                } catch (IOException cancelledClient) { /* Client may deliberately pause or close the server. */ }
            }
        });
        serverThread.setDaemon(true); serverThread.start(); url = "http://127.0.0.1:" + server.getLocalPort() + "/model";
    }
    @After public void cleanup() throws Exception {
        if (server != null) server.close();
        if (serverThread != null) serverThread.join(2000);
        if (directory != null) try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(directory.toPath())) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
    private ResumableDownloader downloader() { return new ResumableDownloader(address -> (HttpURLConnection) address.openConnection()); }
    private File part() { return new File(target.getPath() + ".part"); }
    private void transfer(ResumableDownloader downloader) throws Exception { downloader.download(url, target, payload.length, hash, bytes -> {}); }

    @Test public void interruptedTransferResumesAcrossNewDownloader() throws Exception {
        ResumableDownloader first = downloader();
        try {
            first.download(url, target, payload.length, hash, bytes -> { if (bytes >= 65536) first.cancel(); });
            fail("Expected pause");
        } catch (CancellationException expected) {}
        assertFalse(target.exists()); assertTrue(part().length() >= 65536); assertTrue(part().length() < payload.length);
        long saved = part().length();
        transfer(downloader()); assertEquals("bytes=" + saved + "-", range.get());
        assertArrayEquals(payload, Files.readAllBytes(target.toPath())); assertFalse(part().exists());
    }
    @Test public void serverIgnoringRangeRestartsInsteadOfAppending() throws Exception {
        Files.write(part().toPath(), Arrays.copyOf(payload, 713)); mode = "ignore-range";
        transfer(downloader()); assertEquals("bytes=713-", range.get()); assertEquals(payload.length, target.length());
        assertArrayEquals(payload, Files.readAllBytes(target.toPath()));
    }
    @Test public void invalidRangePreservesSavedBytesAndNeverPublishes() throws Exception {
        byte[] saved = Arrays.copyOf(payload, 321); Files.write(part().toPath(), saved); mode = "invalid-range";
        try { transfer(downloader()); fail("Expected invalid range"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("断点")); }
        assertFalse(target.exists()); assertArrayEquals(saved, Files.readAllBytes(part().toPath()));
    }
    @Test public void checksumFailureRemovesBadPartialAndCanRetry() throws Exception {
        mode = "corrupt";
        try { transfer(downloader()); fail("Expected checksum failure"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("校验")); }
        assertFalse(target.exists()); assertFalse(part().exists());
        mode = "normal"; transfer(downloader()); assertTrue(ResumableDownloader.verified(target, payload.length, hash));
    }
    @Test public void completePartialIsPublishedWithoutNetwork() throws Exception {
        Files.write(part().toPath(), payload); transfer(downloader()); assertEquals(0, requests.get());
        assertTrue(target.exists()); assertFalse(part().exists());
    }
    @Test public void verifiedDownloadIsReusableOffline() throws Exception {
        transfer(downloader()); int completedRequests = requests.get(); server.close();
        transfer(downloader()); assertEquals(completedRequests, requests.get());
    }
    @Test public void corruptFinalOfSameSizeIsRepaired() throws Exception {
        Files.write(target.toPath(), new byte[payload.length]);
        transfer(downloader()); assertArrayEquals(payload, Files.readAllBytes(target.toPath()));
    }
    @Test public void failedResponsePreservesPartialForRetry() throws Exception {
        byte[] saved = Arrays.copyOf(payload, 287); Files.write(part().toPath(), saved); mode = "error";
        try { transfer(downloader()); fail("Expected HTTP failure"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("503")); }
        assertArrayEquals(saved, Files.readAllBytes(part().toPath())); mode = "normal";
        transfer(downloader()); assertEquals("bytes=287-", range.get());
    }
    @Test public void encodedResponseIsNeverWritten() throws Exception {
        mode = "compressed";
        try { transfer(downloader()); fail("Expected compressed response rejection"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("压缩")); }
        assertFalse(target.exists()); assertFalse(part().exists());
    }
    @Test public void productionRejectsPlainHttp() throws Exception {
        try { transfer(new ResumableDownloader()); fail("Expected HTTPS policy"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("HTTPS")); }
        assertEquals(0, requests.get());
    }
}
