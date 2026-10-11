package com.alibaba.mnn.english;

final class SupertonicNative {
    static { System.loadLibrary("english_reader"); }
    static native long create(String modelDirectory, int sampleRate, int baseChunkSize, int compress, int latentDim);
    static native short[] synthesize(long handle, int[] tokens, float[] styleTtl, int[] ttlShape,
                                   float[] styleDp, int[] dpShape, float speed);
    static native void cancel(long handle);
    static native void destroy(long handle);
}
