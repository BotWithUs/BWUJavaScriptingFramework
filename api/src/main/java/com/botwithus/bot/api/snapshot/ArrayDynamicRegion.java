package com.botwithus.bot.api.snapshot;

/**
 * The {@link DynamicRegion#copyOf(DynamicRegion)} result: every scalar and the
 * whole descriptor grid copied onto the heap, safe to retain across ticks.
 *
 * <p>Deliberately not a record — a record would expose the backing {@code int[]}
 * through its accessor and give the type array-identity equality, and the point
 * of this class is that the array is private and frozen at construction.</p>
 */
final class ArrayDynamicRegion implements DynamicRegion {

    private final boolean isInstance;
    private final boolean isTruncated;
    private final int sceneMode;
    private final int originMapX;
    private final int originMapY;
    private final int maxMapX;
    private final int maxMapY;
    private final int gridW;
    private final int gridH;
    private final int requiredChunks;
    private final int[] chunks;

    /**
     * Drains {@code source} eagerly. Reads exactly {@link DynamicRegion#chunkCount()}
     * descriptors — never past the count, because the producer leaves the rest of
     * the published array stale rather than clearing it.
     */
    ArrayDynamicRegion(DynamicRegion source) {
        this.isInstance = source.isInstance();
        this.isTruncated = source.isTruncated();
        this.sceneMode = source.sceneMode();
        this.originMapX = source.originMapX();
        this.originMapY = source.originMapY();
        this.maxMapX = source.maxMapX();
        this.maxMapY = source.maxMapY();
        this.gridW = source.gridW();
        this.gridH = source.gridH();
        this.requiredChunks = source.requiredChunks();
        int count = Math.max(0, source.chunkCount());
        this.chunks = new int[count];
        for (int i = 0; i < count; i++) {
            this.chunks[i] = source.chunkAt(i);
        }
    }

    @Override
    public boolean isInstance() {
        return isInstance;
    }

    @Override
    public boolean isTruncated() {
        return isTruncated;
    }

    @Override
    public int sceneMode() {
        return sceneMode;
    }

    @Override
    public int originMapX() {
        return originMapX;
    }

    @Override
    public int originMapY() {
        return originMapY;
    }

    @Override
    public int maxMapX() {
        return maxMapX;
    }

    @Override
    public int maxMapY() {
        return maxMapY;
    }

    @Override
    public int gridW() {
        return gridW;
    }

    @Override
    public int gridH() {
        return gridH;
    }

    @Override
    public int requiredChunks() {
        return requiredChunks;
    }

    @Override
    public int chunkCount() {
        return chunks.length;
    }

    @Override
    public int chunkAt(int index) {
        if (index < 0 || index >= chunks.length) {
            throw new IndexOutOfBoundsException(index);
        }
        return chunks[index];
    }

    @Override
    public String toString() {
        return "DynamicRegion[instance=" + isInstance
                + ", mode=" + sceneMode
                + ", origin=" + originMapX + "," + originMapY
                + ", grid=" + gridW + "x" + gridH
                + ", chunks=" + chunks.length + "/" + requiredChunks
                + (isTruncated ? ", TRUNCATED]" : "]");
    }
}
