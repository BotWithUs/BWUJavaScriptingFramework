package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.snapshot.DynamicRegion;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Flyweight {@link DynamicRegion} over a snapshot's dynamic-region block.
 *
 * <p>The ten scalars are read once and cached in a {@link DynamicRegionEntry};
 * the descriptor grid is <b>not</b> copied — {@link #chunkAt(int)} reads
 * straight out of a slice of the mapping. Materialising the whole grid would
 * cost 16384 ints per tick and dwarf everything else the snapshot allocates,
 * for data most ticks never look at. Same precedent as {@link LocalPlayerView}:
 * one small object per acquisition, zero allocation per tile lookup.</p>
 *
 * <p>Lifetime is the snapshot's. The producer may overwrite the bytes behind
 * the slice as soon as it publishes the next buffer, so a caller that needs the
 * grid past this tick must take a {@link DynamicRegion#copyOf(DynamicRegion)}.</p>
 */
public final class DynamicRegionView implements DynamicRegion {

    private final DynamicRegionEntry header;
    private final MemorySegment chunks;
    private final int chunkCount;

    /**
     * @param header     the block's scalars, already read
     * @param chunks     a slice covering exactly {@code chunkCount} descriptors
     * @param chunkCount published descriptor count, already clamped to
     *                   {@link Layout#DYN_CHUNK_CAP}
     */
    DynamicRegionView(DynamicRegionEntry header, MemorySegment chunks, int chunkCount) {
        this.header = header;
        this.chunks = chunks;
        this.chunkCount = chunkCount;
    }

    @Override
    public boolean isInstance() {
        return header.isInstance();
    }

    @Override
    public boolean isTruncated() {
        return header.isTruncated();
    }

    @Override
    public int sceneMode() {
        return header.sceneMode();
    }

    @Override
    public int originMapX() {
        return header.originMapX();
    }

    @Override
    public int originMapY() {
        return header.originMapY();
    }

    @Override
    public int maxMapX() {
        return header.maxMapX();
    }

    @Override
    public int maxMapY() {
        return header.maxMapY();
    }

    @Override
    public int gridW() {
        return header.gridW();
    }

    @Override
    public int gridH() {
        return header.gridH();
    }

    @Override
    public int requiredChunks() {
        return header.requiredChunks();
    }

    @Override
    public int chunkCount() {
        return chunkCount;
    }

    @Override
    public int chunkAt(int index) {
        if (index < 0 || index >= chunkCount) {
            throw new IndexOutOfBoundsException(index);
        }
        return chunks.get(ValueLayout.JAVA_INT, (long) index * Integer.BYTES);
    }
}
