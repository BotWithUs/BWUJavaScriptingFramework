package com.botwithus.bot.api.snapshot;

/**
 * The {@link DynamicRegion#STATIC} answer: an ordinary, non-instanced scene.
 *
 * <p>Backs the {@link GameSnapshot#dynamicRegion()} default, so every hand-rolled
 * snapshot stub and every pre-v19 caller gets a region that is honestly empty
 * rather than absent. Stateless and immutable — one shared instance is enough.</p>
 */
final class StaticDynamicRegion implements DynamicRegion {

    /** Mapsquare bounds this region cannot speak to. Nothing reads them: every
     *  resolver call short-circuits on {@link #isInstance()} first. */
    private static final int UNKNOWN_MAPSQUARE = -1;

    StaticDynamicRegion() {
    }

    @Override
    public boolean isInstance() {
        return false;
    }

    @Override
    public boolean isTruncated() {
        return false;
    }

    @Override
    public int sceneMode() {
        return SCENE_MODE_STATIC;
    }

    @Override
    public int originMapX() {
        return UNKNOWN_MAPSQUARE;
    }

    @Override
    public int originMapY() {
        return UNKNOWN_MAPSQUARE;
    }

    @Override
    public int maxMapX() {
        return UNKNOWN_MAPSQUARE;
    }

    @Override
    public int maxMapY() {
        return UNKNOWN_MAPSQUARE;
    }

    @Override
    public int gridW() {
        return 0;
    }

    @Override
    public int gridH() {
        return 0;
    }

    @Override
    public int requiredChunks() {
        return 0;
    }

    @Override
    public int chunkCount() {
        return 0;
    }

    @Override
    public int chunkAt(int index) {
        throw new IndexOutOfBoundsException(index);
    }

    @Override
    public String toString() {
        return "DynamicRegion.STATIC";
    }
}
