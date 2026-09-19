package me.cortex.voxy.common.world;

public final class WorldSectionPositionVerification {
    private WorldSectionPositionVerification() {}

    public static void main(String[] args) {
        verifyLegacyKeys();
        verifyRoundTrips();
        verifyHierarchy();
        verifyRanges();
    }

    private static void verifyLegacyKeys() {
        int[][] positions = {
                {0, 0, 0, 0},
                {4, -26_661, 19, -8_910},
                {0, -852_171, -128, -284_178},
                {0, 1_875_000, 127, -1_875_000}
        };
        for (int[] position : positions) {
            long expected = legacy(position[0], position[1], position[2], position[3]);
            long actual = WorldEngine.getWorldSectionId(position[0], position[1], position[2], position[3]);
            require(actual == expected, "Legacy key changed");
            require((actual&1L) == 0, "Legacy key uses the extended marker");
            verifyDecoded(actual, position[0], position[1], position[2], position[3]);
        }
    }

    private static void verifyRoundTrips() {
        int[][] positions = {
                {0, -852_171, -129, -284_178},
                {0, -426_576, 128, -142_560},
                {0, -426_576, 304, -142_560},
                {0, -852_171, 652, -284_178},
                {0, -852_171, 766, -284_178},
                {15, -(1<<22), -(1<<11), -(1<<23)},
                {15, (1<<22)-1, (1<<11)-1, (1<<23)-1}
        };
        for (int[] position : positions) {
            long id = WorldEngine.getWorldSectionId(position[0], position[1], position[2], position[3]);
            require((id&1L) != 0, "Extended key is missing its marker");
            verifyDecoded(id, position[0], position[1], position[2], position[3]);
        }

        long legacy = WorldEngine.getWorldSectionId(0, 0, 127, 0);
        long extended = WorldEngine.getWorldSectionId(0, 0, 128, 0);
        require(legacy != extended, "Legacy and extended keys collide");
    }

    private static void verifyHierarchy() {
        int baseX = -852_171;
        int baseY = 652;
        int baseZ = -284_178;
        for (int level = 0; level <= WorldEngine.MAX_LOD_LAYER; level++) {
            int x = baseX>>level;
            int y = baseY>>level;
            int z = baseZ>>level;
            long parent = WorldEngine.getWorldSectionId(level, x, y, z);
            verifyDecoded(parent, level, x, y, z);

            int childX = x<<level;
            int childY = y<<level;
            int childZ = z<<level;
            long child = WorldEngine.getWorldSectionId(0, childX, childY, childZ);
            verifyDecoded(child, 0, childX, childY, childZ);
        }
    }

    private static void verifyRanges() {
        expectOutOfRange(-(1<<22)-1, 128, 0);
        expectOutOfRange(1<<22, 128, 0);
        expectOutOfRange(0, -(1<<11)-1, 0);
        expectOutOfRange(0, 1<<11, 0);
        expectOutOfRange(0, 128, -(1<<23)-1);
        expectOutOfRange(0, 128, 1<<23);
    }

    private static void verifyDecoded(long id, int level, int x, int y, int z) {
        require(WorldEngine.getLevel(id) == level, "Level round trip failed");
        require(WorldEngine.getX(id) == x, "X round trip failed");
        require(WorldEngine.getY(id) == y, "Y round trip failed");
        require(WorldEngine.getZ(id) == z, "Z round trip failed");

        int[] gpu = decodeGpu(id);
        require(gpu[0] == x && gpu[1] == y && gpu[2] == z, "GPU decoder disagrees with CPU decoder");
    }

    private static int[] decodeGpu(long id) {
        int high = (int) (id>>>32);
        int low = (int) id;
        if ((low&1) != 0) {
            int x = (low<<8)>>9;
            int y = (high<<4)>>20;
            int z = (((high&0xFFFF)<<8)|(low>>>24))<<8>>8;
            return new int[]{x, y, z};
        }
        int x = (low<<4)>>8;
        int y = (high<<4)>>24;
        int z = (((high&0xFFFFF)<<4)|(low>>>28))<<8>>8;
        return new int[]{x, y, z};
    }

    private static long legacy(int level, int x, int y, int z) {
        return ((long)level<<60)|((long)(y&0xFF)<<52)|((long)(z&0xFFFFFF)<<28)|((long)(x&0xFFFFFF)<<4);
    }

    private static void expectOutOfRange(int x, int y, int z) {
        try {
            WorldEngine.getWorldSectionId(0, x, y, z);
            throw new AssertionError("Out-of-range position was accepted");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
