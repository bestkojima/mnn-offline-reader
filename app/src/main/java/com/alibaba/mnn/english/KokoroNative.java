package com.alibaba.mnn.english;

final class KokoroNative {
    static { System.loadLibrary("english_reader"); }
    static native long create(String model);
    static native short[] synthesize(long handle, int[] tokens, float[] style, float speed);
    static native void cancel(long handle);
    static native void destroy(long handle);
    private KokoroNative() {}
}
