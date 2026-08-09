package com.botwithus.bot.api.snapshot;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure resolver tests for {@link DynamicRegion} — no shared memory, no host.
 * Everything here is arithmetic over hand-built descriptor grids.
 *
 * <p>The rotation cases carry unusual weight: <b>rotation has never been
 * observed non-zero in a live capture</b> (a player-owned house turns out to be
 * a flat 1:1 contiguous copy, so it cannot exercise it). Until someone captures
 * a Dungeoneering floor, these tests are the only coverage the rotation path
 * will ever get. The expected values below are re-stated from the client's own
 * rotation switch rather than imported from {@link DynamicRegion}, so an edit to
 * the production code fails here instead of agreeing with itself.</p>
 */
class DynamicRegionTest {

    // Bit shifts and the chunk edge re-stated from the RE capture rather than
    // imported from DynamicRegion — importing them would make every decode test
    // a tautology.
    private static final int PLANE_SHIFT = 24;
    private static final int CHUNK_X_SHIFT = 14;
    private static final int CHUNK_Y_SHIFT = 3;
    private static final int ROTATION_SHIFT = 1;
    private static final int CHUNK_EDGE = 8;
    private static final int PLANES = 4;
    /** Tiles along one edge of a mapsquare, and its log2 — the tile>>6 that turns
     *  a world tile back into the mapsquare an expectation is stated in. */
    private static final int MAPSQUARE_TILES = 64;
    private static final int MAPSQUARE_TILE_SHIFT = 6;
    /** Chunks along one edge of a mapsquare: the shift that turns a source chunk
     *  index into a source mapsquare. */
    private static final int CHUNKS_PER_MAPSQUARE_SHIFT = 3;
    /** Written over a source grid after copying, to prove the copy does not alias it. */
    private static final int POISON = 0x0BADF00D;

    // The live player-owned-house capture: a 32x32-chunk grid whose origin is
    // mapsquare (250, 6) and whose loaded window runs to mapsquare (254, 10).
    private static final int POH_ORIGIN_MAP_X = 250;
    private static final int POH_ORIGIN_MAP_Y = 6;
    private static final int POH_MAX_MAP_X = 254;
    private static final int POH_MAX_MAP_Y = 10;
    private static final int POH_GRID = 32;
    private static final int POH_SCENE_MODE = 6;

    /** The one descriptor read verbatim out of a running client. */
    private static final int POH_DESCRIPTOR = 0x00A00040;

    // ------------------------------------------------------------------
    // Descriptor decode
    // ------------------------------------------------------------------

    @Test
    void decodesTheLivePohDescriptor() {
        assertFalse(DynamicRegion.isHole(POH_DESCRIPTOR));
        assertEquals(0, DynamicRegion.descPlane(POH_DESCRIPTOR));
        assertEquals(640, DynamicRegion.descChunkX(POH_DESCRIPTOR));
        assertEquals(8, DynamicRegion.descChunkY(POH_DESCRIPTOR));
        assertEquals(0, DynamicRegion.descRotation(POH_DESCRIPTOR));
        // Source mapsquare is chunk >> 3: the house template region (80, 1).
        assertEquals(80, DynamicRegion.descChunkX(POH_DESCRIPTOR) >> CHUNKS_PER_MAPSQUARE_SHIFT);
        assertEquals(1, DynamicRegion.descChunkY(POH_DESCRIPTOR) >> CHUNKS_PER_MAPSQUARE_SHIFT);
    }

    /** Every field at its maximum at once: the four widths must tile the word
     *  without overlapping, or a maxed neighbour bleeds into the field next door. */
    @Test
    void fieldMaximaDecodeWithoutOverlap() {
        int packed = descriptor(3, 1023, 2047, 3);

        assertFalse(DynamicRegion.isHole(packed), "0x03FFFFFE must not read as a hole");
        assertEquals(3, DynamicRegion.descPlane(packed));
        assertEquals(1023, DynamicRegion.descChunkX(packed));
        assertEquals(2047, DynamicRegion.descChunkY(packed));
        assertEquals(3, DynamicRegion.descRotation(packed));
    }

    @Test
    void negativeDescriptorsAreHolesEvenWhenNotMinusOne() {
        assertTrue(DynamicRegion.isHole(DynamicRegion.NO_CHUNK));
        assertTrue(DynamicRegion.isHole(-1));
        // A malformed value with the sign bit set must degrade to "no source"
        // rather than decode into plausible-looking coordinates.
        assertTrue(DynamicRegion.isHole(Integer.MIN_VALUE));
        assertFalse(DynamicRegion.isHole(0), "an all-zero descriptor is plane 0 chunk 0, not a hole");
    }

    // ------------------------------------------------------------------
    // Rotation
    // ------------------------------------------------------------------

    /**
     * All four rotations over all 64 local positions, against the client's
     * switch written out longhand: {@code r0 (x,y)}, {@code r1 (y, 7-x)},
     * {@code r2 (7-x, 7-y)}, {@code r3 (7-y, x)}.
     */
    @Test
    void allRotationsMatchTheClientSwitchOverEveryLocalPosition() {
        for (int rotation = 0; rotation < PLANES; rotation++) {
            for (int localX = 0; localX < CHUNK_EDGE; localX++) {
                for (int localY = 0; localY < CHUNK_EDGE; localY++) {
                    String at = "rot=" + rotation + " local=(" + localX + "," + localY + ")";
                    assertEquals(expectedSourceX(localX, localY, rotation),
                            DynamicRegion.rotateLocalX(localX, localY, rotation), at + " x");
                    assertEquals(expectedSourceY(localX, localY, rotation),
                            DynamicRegion.rotateLocalY(localX, localY, rotation), at + " y");
                }
            }
        }
    }

    /**
     * A rotation is a rigid motion of the chunk, so the 64 source-local
     * positions it produces must be exactly the 64 destination-local positions
     * — no duplicates, no escapes outside {@code 0..7}. A sign slip or an
     * off-by-one in the {@code 7 - v} terms breaks this even when individual
     * cases look plausible.
     */
    @Test
    void everyRotationIsAPermutationOfTheChunk() {
        for (int rotation = 0; rotation < PLANES; rotation++) {
            Set<Integer> seen = new HashSet<>();
            for (int localX = 0; localX < CHUNK_EDGE; localX++) {
                for (int localY = 0; localY < CHUNK_EDGE; localY++) {
                    int sourceX = DynamicRegion.rotateLocalX(localX, localY, rotation);
                    int sourceY = DynamicRegion.rotateLocalY(localX, localY, rotation);
                    assertTrue(sourceX >= 0 && sourceX < CHUNK_EDGE, "sourceX in range");
                    assertTrue(sourceY >= 0 && sourceY < CHUNK_EDGE, "sourceY in range");
                    assertTrue(seen.add(sourceX * CHUNK_EDGE + sourceY),
                            "rot=" + rotation + " maps two tiles onto (" + sourceX + "," + sourceY + ")");
                }
            }
            assertEquals(CHUNK_EDGE * CHUNK_EDGE, seen.size(), "rot=" + rotation + " covers the chunk");
        }
    }

    @Test
    void rotationUsesOnlyTheTwoWireBits() {
        // The wire field is 2 bits; a caller passing a wider value must wrap
        // rather than fall through to the identity arm.
        assertEquals(DynamicRegion.rotateLocalX(3, 5, 1), DynamicRegion.rotateLocalX(3, 5, 5));
        assertEquals(DynamicRegion.rotateLocalY(3, 5, 1), DynamicRegion.rotateLocalY(3, 5, 5));
    }

    @Test
    void locRotationComposesModuloFour() {
        assertEquals(0, DynamicRegion.rotateLocRotation(0, 0));
        assertEquals(3, DynamicRegion.rotateLocRotation(1, 2));
        assertEquals(0, DynamicRegion.rotateLocRotation(2, 2));
        assertEquals(2, DynamicRegion.rotateLocRotation(3, 3));
    }

    // ------------------------------------------------------------------
    // Resolver
    // ------------------------------------------------------------------

    /**
     * End-to-end replay of the live house capture. Mapsquare (252, 8) sits at
     * grid chunk (16, 16); its first tile is (16128, 512), and the descriptor
     * there resolves into the template region (80, 1).
     */
    @Test
    void resolvesThePohCaptureToItsTemplateRegion() {
        int[] chunks = new int[PLANES * POH_GRID * POH_GRID];
        chunks[index(0, 16, 16, POH_GRID, POH_GRID)] = POH_DESCRIPTOR;
        DynamicRegion region = poh(chunks);

        SourceTile source = region.sourceOf(16128, 512, 0).orElseThrow();

        assertEquals(5120, source.tileX(), "640 chunks * 8 tiles");
        assertEquals(64, source.tileY(), "8 chunks * 8 tiles");
        assertEquals(0, source.plane());
        assertEquals(0, source.rotation());
        // Source mapsquare (80, 1) — tile >> 6.
        assertEquals(80, source.tileX() >> MAPSQUARE_TILE_SHIFT);
        assertEquals(1, source.tileY() >> MAPSQUARE_TILE_SHIFT);
    }

    /** With rotation 0 the copy is 1:1, so walking one tile east in the instance
     *  must walk one tile east in the source. */
    @Test
    void unrotatedChunksTranslateOneToOne() {
        int[] chunks = new int[PLANES * POH_GRID * POH_GRID];
        chunks[index(0, 16, 16, POH_GRID, POH_GRID)] = POH_DESCRIPTOR;
        DynamicRegion region = poh(chunks);

        SourceTile here = region.sourceOf(16128, 512, 0).orElseThrow();
        SourceTile east = region.sourceOf(16129, 512, 0).orElseThrow();

        assertEquals(here.tileX() + 1, east.tileX());
        assertEquals(here.tileY(), east.tileY());
    }

    @Test
    void resolverAppliesChunkRotationToTheLocalOffset() {
        DynamicRegion region = singleChunk(descriptor(0, 100, 200, 1));

        SourceTile source = region.sourceOf(3, 5, 0).orElseThrow();

        // rot 1: sx = ly = 5, sy = 7 - lx = 4.
        assertEquals(100 * CHUNK_EDGE + 5, source.tileX());
        assertEquals(200 * CHUNK_EDGE + 4, source.tileY());
        assertEquals(1, source.rotation());
    }

    /**
     * Plane-major, and the plane stride is {@code gridW * gridH}: with a
     * non-square grid a transposed index lands on a different cell instead of
     * silently agreeing.
     */
    @Test
    void gridIndexIsPlaneMajorAndNotTransposed() {
        int gridW = 3;
        int gridH = 5;
        int[] chunks = new int[PLANES * gridW * gridH];
        // Give every cell a descriptor that encodes its own coordinates.
        for (int plane = 0; plane < PLANES; plane++) {
            for (int gridX = 0; gridX < gridW; gridX++) {
                for (int gridY = 0; gridY < gridH; gridY++) {
                    chunks[index(plane, gridX, gridY, gridW, gridH)] =
                            descriptor(plane, 500 + gridX, 700 + gridY, 0);
                }
            }
        }
        DynamicRegion region = new TestRegion(true, false, POH_SCENE_MODE,
                0, 0, 0, 0, gridW, gridH, PLANES * gridW * gridH, chunks);

        // Grid cell (2, 4) on plane 3 — the only cell where a transposed or
        // plane-minor index would still be in bounds but wrong.
        int descriptorAt = region.chunkDescriptorAt(2 * CHUNK_EDGE, 4 * CHUNK_EDGE, 3);

        assertEquals(3, DynamicRegion.descPlane(descriptorAt));
        assertEquals(502, DynamicRegion.descChunkX(descriptorAt));
        assertEquals(704, DynamicRegion.descChunkY(descriptorAt));
    }

    @Test
    void holesResolveToNoSource() {
        DynamicRegion region = singleChunk(DynamicRegion.NO_CHUNK);

        assertEquals(DynamicRegion.NO_CHUNK, region.chunkDescriptorAt(0, 0, 0));
        assertEquals(DynamicRegion.NO_SOURCE, region.sourceOfPacked(0, 0, 0));
        assertTrue(region.sourceOf(0, 0, 0).isEmpty());
    }

    /**
     * The loaded window is not the grid. In the live capture the client had a
     * 5x5 mapsquare (40x40 chunk) window over a 32x32 chunk grid, so a tile can
     * sit comfortably inside {@code maxMapX}/{@code maxMapY} and still have no
     * source. Reporting that as a bug is the failure mode this test documents.
     */
    @Test
    void tileInsideTheWindowButOutsideTheGridHasNoSource() {
        int[] chunks = new int[PLANES * POH_GRID * POH_GRID];
        Arrays.fill(chunks, POH_DESCRIPTOR);
        DynamicRegion region = poh(chunks);

        // Grid chunk (35, 35): past the 32-chunk grid, but mapsquare 254 is
        // still inside the window that maxMapX/maxMapY describe.
        int tileX = (POH_ORIGIN_MAP_X * CHUNK_EDGE + 35) * CHUNK_EDGE;
        int tileY = (POH_ORIGIN_MAP_Y * CHUNK_EDGE + 35) * CHUNK_EDGE;

        assertTrue(tileX >> MAPSQUARE_TILE_SHIFT <= region.maxMapX(), "test tile really is inside the window");
        assertTrue(tileY >> MAPSQUARE_TILE_SHIFT <= region.maxMapY(), "test tile really is inside the window");
        assertEquals(DynamicRegion.NO_CHUNK, region.chunkDescriptorAt(tileX, tileY, 0));
        assertTrue(region.sourceOf(tileX, tileY, 0).isEmpty());
    }

    @Test
    void tilesBeforeTheOriginHaveNoSource() {
        int[] chunks = new int[PLANES * POH_GRID * POH_GRID];
        Arrays.fill(chunks, POH_DESCRIPTOR);
        DynamicRegion region = poh(chunks);

        int beforeOrigin = POH_ORIGIN_MAP_X * MAPSQUARE_TILES - 1;
        assertEquals(DynamicRegion.NO_CHUNK, region.chunkDescriptorAt(beforeOrigin, 512, 0));
    }

    @Test
    void planesOutsideZeroToThreeHaveNoSource() {
        DynamicRegion region = singleChunk(POH_DESCRIPTOR);

        assertEquals(DynamicRegion.NO_CHUNK, region.chunkDescriptorAt(0, 0, -1));
        assertEquals(DynamicRegion.NO_CHUNK, region.chunkDescriptorAt(0, 0, PLANES));
    }

    /**
     * A grid whose published count falls short of {@code requiredChunks} — the
     * shape a torn or clamped read leaves behind. Cells inside the count still
     * resolve; cells past it read as holes rather than running off the array.
     */
    @Test
    void shortCountLeavesTrailingCellsUnresolvable() {
        int gridW = 2;
        int gridH = 2;
        int published = 3;
        int[] chunks = new int[published];
        Arrays.fill(chunks, POH_DESCRIPTOR);
        DynamicRegion region = new TestRegion(true, false, POH_SCENE_MODE,
                0, 0, 0, 0, gridW, gridH, PLANES * gridW * gridH, chunks);

        assertEquals(PLANES * gridW * gridH, region.requiredChunks());
        assertEquals(published, region.chunkCount());
        // Index 0 (plane 0, cell 0,0) is published; index 3 (plane 0, cell 1,1) is not.
        assertNotEquals(DynamicRegion.NO_CHUNK, region.chunkDescriptorAt(0, 0, 0));
        assertEquals(DynamicRegion.NO_CHUNK,
                region.chunkDescriptorAt(CHUNK_EDGE, CHUNK_EDGE, 0));
    }

    @Test
    void chunkAtRejectsIndicesPastTheCount() {
        DynamicRegion region = singleChunk(POH_DESCRIPTOR);

        assertThrows(IndexOutOfBoundsException.class, () -> region.chunkAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> region.chunkAt(region.chunkCount()));
    }

    // ------------------------------------------------------------------
    // Packed source-tile codec
    // ------------------------------------------------------------------

    @Test
    void packedSourceRoundTripsAndNeverCollidesWithNoSource() {
        long packed = DynamicRegion.packSource(8191, 16383, 3, 3);

        assertEquals(8191, DynamicRegion.srcTileX(packed));
        assertEquals(16383, DynamicRegion.srcTileY(packed));
        assertEquals(3, DynamicRegion.srcPlane(packed));
        assertEquals(3, DynamicRegion.srcRotation(packed));
        assertTrue(packed >= 0, "every real answer is non-negative, so NO_SOURCE (-1) is unambiguous");
        assertNotEquals(DynamicRegion.NO_SOURCE, packed);
    }

    @Test
    void packedResolverAgreesWithTheOptionalOne() {
        DynamicRegion region = singleChunk(descriptor(2, 300, 400, 3));

        long packed = region.sourceOfPacked(6, 2, 0);
        SourceTile boxed = region.sourceOf(6, 2, 0).orElseThrow();

        assertEquals(boxed.tileX(), DynamicRegion.srcTileX(packed));
        assertEquals(boxed.tileY(), DynamicRegion.srcTileY(packed));
        assertEquals(boxed.plane(), DynamicRegion.srcPlane(packed));
        assertEquals(boxed.rotation(), DynamicRegion.srcRotation(packed));
    }

    // ------------------------------------------------------------------
    // STATIC and copyOf
    // ------------------------------------------------------------------

    @Test
    void staticRegionResolvesNothing() {
        DynamicRegion region = DynamicRegion.STATIC;

        assertFalse(region.isInstance());
        assertFalse(region.isTruncated());
        assertEquals(DynamicRegion.SCENE_MODE_STATIC, region.sceneMode());
        assertEquals(0, region.gridW());
        assertEquals(0, region.gridH());
        assertEquals(0, region.requiredChunks());
        assertEquals(0, region.chunkCount());
        assertEquals(DynamicRegion.NO_CHUNK, region.chunkDescriptorAt(3200, 3200, 0));
        assertTrue(region.sourceOf(3200, 3200, 0).isEmpty());
        assertThrows(IndexOutOfBoundsException.class, () -> region.chunkAt(0));
    }

    @Test
    void copyOfSnapshotsTheGridAndEveryScalar() {
        int[] chunks = new int[PLANES];
        Arrays.fill(chunks, POH_DESCRIPTOR);
        DynamicRegion source = new TestRegion(true, true, POH_SCENE_MODE,
                POH_ORIGIN_MAP_X, POH_ORIGIN_MAP_Y, POH_MAX_MAP_X, POH_MAX_MAP_Y,
                POH_GRID, POH_GRID + 1, 9999, chunks);

        DynamicRegion copy = DynamicRegion.copyOf(source);
        chunks[0] = POISON;

        assertTrue(copy.isInstance());
        assertTrue(copy.isTruncated(), "truncation flag must survive the copy");
        assertEquals(POH_SCENE_MODE, copy.sceneMode());
        assertEquals(POH_ORIGIN_MAP_X, copy.originMapX());
        assertEquals(POH_ORIGIN_MAP_Y, copy.originMapY());
        assertEquals(POH_MAX_MAP_X, copy.maxMapX());
        assertEquals(POH_MAX_MAP_Y, copy.maxMapY());
        assertEquals(POH_GRID, copy.gridW());
        assertEquals(POH_GRID + 1, copy.gridH());
        assertEquals(9999, copy.requiredChunks(), "requiredChunks survives even when it disagrees");
        assertEquals(PLANES, copy.chunkCount());
        assertEquals(POH_DESCRIPTOR, copy.chunkAt(0), "the copy must not alias the source grid");
    }

    @Test
    void copyOfAStaticRegionIsStillStatic() {
        DynamicRegion copy = DynamicRegion.copyOf(DynamicRegion.STATIC);

        assertFalse(copy.isInstance());
        assertEquals(DynamicRegion.SCENE_MODE_STATIC, copy.sceneMode());
        assertEquals(0, copy.chunkCount());
        assertTrue(copy.sourceOf(3200, 3200, 0).isEmpty());
    }

    @Test
    void copyOfResolvesIdenticallyToItsSource() {
        int[] chunks = new int[PLANES * POH_GRID * POH_GRID];
        chunks[index(0, 16, 16, POH_GRID, POH_GRID)] = POH_DESCRIPTOR;
        DynamicRegion source = poh(chunks);

        Optional<SourceTile> viaSource = source.sourceOf(16130, 515, 0);
        Optional<SourceTile> viaCopy = DynamicRegion.copyOf(source).sourceOf(16130, 515, 0);

        assertEquals(viaSource, viaCopy);
    }

    @Test
    void copyOfStableSucceedsWhenThePublishCounterHoldsStill() {
        int[] chunks = new int[PLANES * POH_GRID * POH_GRID];
        chunks[index(0, 16, 16, POH_GRID, POH_GRID)] = POH_DESCRIPTOR;
        GameSnapshot snapshot = new TickingSnapshot(poh(chunks), 0);

        DynamicRegion copy = DynamicRegion.copyOfStable(snapshot).orElseThrow();

        assertEquals(POH_DESCRIPTOR, copy.chunkDescriptorAt(16128, 512, 0));
    }

    /**
     * A producer republishing under every read can never yield a coherent copy.
     * The contract is an empty {@link Optional} — "ask again next tick" — and
     * emphatically not {@link DynamicRegion#STATIC}, which would read as "this
     * scene is not an instance" and be the silent wrong answer.
     */
    @Test
    void copyOfStableGivesUpRatherThanReturningATornGrid() {
        int[] chunks = new int[PLANES * POH_GRID * POH_GRID];
        GameSnapshot snapshot = new TickingSnapshot(poh(chunks), 1);

        assertTrue(DynamicRegion.copyOfStable(snapshot).isEmpty());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The client's rotation switch, longhand: r0 x, r1 y, r2 7-x, r3 7-y. */
    private static int expectedSourceX(int localX, int localY, int rotation) {
        if (rotation == 1) {
            return localY;
        }
        if (rotation == 2) {
            return DynamicRegion.CHUNK_MAX_LOCAL - localX;
        }
        if (rotation == 3) {
            return DynamicRegion.CHUNK_MAX_LOCAL - localY;
        }
        return localX;
    }

    /** The client's rotation switch, longhand: r0 y, r1 7-x, r2 7-y, r3 x. */
    private static int expectedSourceY(int localX, int localY, int rotation) {
        if (rotation == 1) {
            return DynamicRegion.CHUNK_MAX_LOCAL - localX;
        }
        if (rotation == 2) {
            return DynamicRegion.CHUNK_MAX_LOCAL - localY;
        }
        if (rotation == 3) {
            return localX;
        }
        return localY;
    }

    private static int descriptor(int plane, int chunkX, int chunkY, int rotation) {
        return (plane << PLANE_SHIFT)
                | (chunkX << CHUNK_X_SHIFT)
                | (chunkY << CHUNK_Y_SHIFT)
                | (rotation << ROTATION_SHIFT);
    }

    private static int index(int plane, int gridX, int gridY, int gridW, int gridH) {
        return ((plane * gridW) + gridX) * gridH + gridY;
    }

    /** A region shaped like the live player-owned-house capture. */
    private static DynamicRegion poh(int[] chunks) {
        return new TestRegion(true, false, POH_SCENE_MODE,
                POH_ORIGIN_MAP_X, POH_ORIGIN_MAP_Y, POH_MAX_MAP_X, POH_MAX_MAP_Y,
                POH_GRID, POH_GRID, PLANES * POH_GRID * POH_GRID, chunks);
    }

    /** A 1x1-chunk grid at the world origin, so grid cell (0,0) is tiles 0..7. */
    private static DynamicRegion singleChunk(int planeZeroDescriptor) {
        int[] chunks = new int[PLANES];
        Arrays.fill(chunks, DynamicRegion.NO_CHUNK);
        chunks[0] = planeZeroDescriptor;
        return new TestRegion(true, false, POH_SCENE_MODE, 0, 0, 0, 0, 1, 1, PLANES, chunks);
    }

    /**
     * Minimal {@link GameSnapshot} for the {@code copyOfStable} tests: every
     * accessor {@code copyOfStable} does not touch is a stub, and
     * {@link #publishSeq()} advances by {@code advancePerRead} on each read, so
     * {@code 0} models a quiet producer and {@code 1} one republishing under
     * every copy attempt.
     */
    private static final class TickingSnapshot implements GameSnapshot {

        private final DynamicRegion region;
        private final long advancePerRead;
        private long publishSeq;

        TickingSnapshot(DynamicRegion region, long advancePerRead) {
            this.region = region;
            this.advancePerRead = advancePerRead;
        }

        @Override
        public long publishSeq() {
            long current = publishSeq;
            publishSeq += advancePerRead;
            return current;
        }

        @Override
        public DynamicRegion dynamicRegion() {
            return region;
        }

        @Override
        public int serverTick() {
            return 0;
        }

        @Override
        public int gameCycle() {
            return 0;
        }

        @Override
        public int gameState() {
            return 0;
        }

        @Override
        public int ownIndex() {
            return -1;
        }

        @Override
        public int sceneVersion() {
            return 0;
        }

        @Override
        public LocalPlayer self() {
            return null;
        }

        @Override
        public Npcs npcs() {
            return null;
        }

        @Override
        public Players players() {
            return null;
        }

        @Override
        public Locations locations() {
            return null;
        }

        @Override
        public Inventories inventories() {
            return null;
        }

        @Override
        public GroundItems groundItems() {
            return null;
        }

        @Override
        public Projectiles projectiles() {
            return null;
        }
    }

    private record TestRegion(
            boolean isInstance,
            boolean isTruncated,
            int sceneMode,
            int originMapX,
            int originMapY,
            int maxMapX,
            int maxMapY,
            int gridW,
            int gridH,
            int requiredChunks,
            int[] chunks
    ) implements DynamicRegion {

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
    }
}
