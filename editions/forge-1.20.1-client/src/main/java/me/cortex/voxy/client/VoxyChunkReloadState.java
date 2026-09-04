package me.cortex.voxy.client;

public final class VoxyChunkReloadState {
    private static long suppressIngestUntil;
    private static long suppressRenderUntil;

    private VoxyChunkReloadState() {}

    public static void suppressIngestFor(long millis) {
        long target = System.currentTimeMillis() + millis;
        if (target > suppressIngestUntil) {
            suppressIngestUntil = target;
        }
    }

    public static boolean isIngestSuppressed() {
        return System.currentTimeMillis() < suppressIngestUntil;
    }

    public static void suppressRenderingFor(long millis) {
        long target = System.currentTimeMillis() + millis;
        if (target > suppressRenderUntil) {
            suppressRenderUntil = target;
        }
    }

    public static boolean isRenderingSuppressed() {
        return System.currentTimeMillis() < suppressRenderUntil;
    }
}
