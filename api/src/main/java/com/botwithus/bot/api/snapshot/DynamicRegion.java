package com.botwithus.bot.api.snapshot;

import java.util.Optional;

/**
 * The scene's dynamic-region (instance) chunk-descriptor grid (v19+).
 *
 * <p>RS3 does not build instances — player-owned houses, Dungeoneering floors,
 * boss rooms — out of dedicated map data. It assembles them by stamping
 * <b>8x8-tile chunks copied out of ordinary static regions</b>, following a
 * table the server sends when it rebuilds the scene. This interface is that
 * table, published verbatim in the snapshot, plus the arithmetic to read it.</p>
 *
 * <p>The reason it exists: a navigation layer baked against static map data is
 * blind inside an instance. Given this grid it stops being blind — every
 * instance tile can be mapped back to the static tile it was copied from, and
 * the baked data for <i>that</i> tile is the answer.</p>
 *
 * <h2>Reading it</h2>
 *
 * <p>The one call most callers want is {@link #sourceOf(int, int, int)}:</p>
 * <pre>{@code
 *   DynamicRegion region = snapshot.dynamicRegion();
 *   region.sourceOf(self.tileX(), self.tileY(), self.plane())
 *         .ifPresent(src -> log.info("standing on a copy of {},{}", src.tileX(), src.tileY()));
 * }</pre>
 * <p>Per-tile loops should use {@link #sourceOfPacked(int, int, int)} instead,
 * which returns the same answer as a packed {@code long} and allocates
 * nothing.</p>
 *
 * <h2>Lifetime</h2>
 *
 * <p>The instance handed out by {@link GameSnapshot#dynamicRegion()} is a
 * flyweight over shared memory and inherits the snapshot's tick lifetime — the
 * producer may overwrite the bytes it points at as soon as the next snapshot is
 * published. Reconstructing instanced collision is not a within-one-tick job,
 * so anything that needs to outlive the tick must take a heap copy with
 * {@link #copyOf(DynamicRegion)} and key it on {@link GameSnapshot#sceneVersion()}.</p>
 *
 * <h2>Three ways this surprises people</h2>
 *
 * <ol>
 *   <li><b>UNITS TRAP.</b> {@link #originMapX()} and {@link #maxMapX()} are
 *       <i>mapsquares</i> (64 tiles); {@link #gridW()} and {@link #gridH()} are
 *       <i>chunks</i> (8 tiles). One mapsquare is 8 chunks. The conversion
 *       happens exactly once, inside
 *       {@link #chunkDescriptorAt(int, int, int)} — callers pass world tiles and
 *       never do it themselves.</li>
 *   <li><b>{@link #maxMapX()}/{@link #maxMapY()} do not bound the resolvable
 *       area.</b> They describe the client's loaded window, which can be
 *       <i>larger</i> than the descriptor grid — a live capture had a 5x5
 *       mapsquare window over a 32x32 chunk (4x4 mapsquare) grid. "The tile is
 *       inside the loaded window" therefore does not imply "the tile has a
 *       source". An empty {@link #sourceOf(int, int, int)} for a tile you can
 *       see is normal, not a bug.</li>
 *   <li><b>Rotation has never been witnessed non-zero.</b> The semantics come
 *       from the client's own explicit switch and are high-confidence, but every
 *       instance captured so far (a player-owned house is a flat 1:1 contiguous
 *       copy) had {@code rotation == 0} throughout. {@link #rotateLocalX(int, int, int)},
 *       {@link #rotateLocalY(int, int, int)} and {@link #rotateLocRotation(int, int)}
 *       are covered by unit tests only. Treat pathing through a rotated instance
 *       as unvalidated until someone captures a Dungeoneering floor.</li>
 * </ol>
 *
 * <p>Mirrors {@code ipc::DynamicRegion} + {@code dynChunks[]} in NXTLibrary's
 * SharedLayout.h. The byte offsets live on the host's wire-layout constants; the
 * <b>bit</b> layout of a descriptor lives here, because scripts decode it and
 * this module cannot depend on the host.</p>
 */
public interface DynamicRegion {

    // ------------------------------------------------------------------
    // Geometry constants
    // ------------------------------------------------------------------

    /** Planes in a scene, and the outer dimension of the descriptor grid. */
    int PLANE_COUNT = 4;

    /** Tiles along one edge of a chunk. The unit the whole descriptor table is in. */
    int CHUNK_TILES = 8;

    /** {@code tileX >> CHUNK_SHIFT} is the chunk X containing that tile. */
    int CHUNK_SHIFT = 3;

    /** {@code tileX & CHUNK_TILE_MASK} is the tile's position within its chunk.
     *  A <b>mask</b>: only ever the right operand of an {@code &}. */
    int CHUNK_TILE_MASK = CHUNK_TILES - 1;

    /** Highest local coordinate inside a chunk — the pivot the rotation math
     *  reflects around, as in {@code CHUNK_MAX_LOCAL - localX}. Numerically equal
     *  to {@link #CHUNK_TILE_MASK}, but a <b>coordinate</b>, not a mask: "mask
     *  minus x" is nonsense, and keeping the two spellings apart is what stops
     *  the next reader from believing it. */
    int CHUNK_MAX_LOCAL = CHUNK_TILES - 1;

    /** {@code mapX << MAPSQUARE_CHUNK_SHIFT} converts a mapsquare to its first
     *  chunk — the x8 in the UNITS TRAP above. */
    int MAPSQUARE_CHUNK_SHIFT = 3;

    /** Rotations are {@code 0..3} in 90-degree steps. */
    int ROTATION_COUNT = 4;

    /** Highest valid rotation — the inclusive bound for a range check. */
    int MAX_ROTATION = ROTATION_COUNT - 1;

    /** Composing two rotations wraps with this mask. Numerically equal to
     *  {@link #MAX_ROTATION}; a mask is not a bound, so don't swap them. */
    int ROTATION_MASK = ROTATION_COUNT - 1;

    /** {@link #sceneMode()} value for an ordinary, non-instanced scene. */
    int SCENE_MODE_STATIC = 3;

    // ------------------------------------------------------------------
    // Packed chunk-descriptor bit layout
    //
    // A descriptor is the client's raw 26-bit code, carried in a u32 and
    // surfaced unmodified:
    //
    //   bits 24-25  source plane        (2 bits, 0..3)
    //   bits 14-23  source chunk X      (10 bits, 0..1023)
    //   bits  3-13  source chunk Y      (11 bits, 0..2047)
    //   bits  1-2   rotation            (2 bits, 0..3)
    //   bit   0     unused
    //
    // A negative value means "no source chunk" — a hole in the instance.
    // Every extractor uses >>> so a hole never sign-extends into a field.
    // ------------------------------------------------------------------

    /** Descriptor value meaning "this grid cell has no source chunk". */
    int NO_CHUNK = -1;

    int DESC_PLANE_SHIFT = 24;
    int DESC_PLANE_MASK = 0x3;
    int DESC_CHUNK_X_SHIFT = 14;
    int DESC_CHUNK_X_MASK = 0x3FF;
    int DESC_CHUNK_Y_SHIFT = 3;
    int DESC_CHUNK_Y_MASK = 0x7FF;
    int DESC_ROTATION_SHIFT = 1;
    int DESC_ROTATION_MASK = 0x3;

    // ------------------------------------------------------------------
    // Packed source-tile bit layout (the allocation-free resolver result)
    //
    //   bits  0-15  source tile X
    //   bits 16-31  source tile Y
    //   bits 32-33  source plane
    //   bits 34-35  rotation
    //
    // The top 28 bits are always zero, so NO_SOURCE (-1) can never collide
    // with a real answer.
    // ------------------------------------------------------------------

    /** {@link #sourceOfPacked(int, int, int)} result meaning "no source tile". */
    long NO_SOURCE = -1L;

    int SRC_TILE_X_SHIFT = 0;
    int SRC_TILE_Y_SHIFT = 16;
    int SRC_PLANE_SHIFT = 32;
    int SRC_ROTATION_SHIFT = 34;
    long SRC_TILE_MASK = 0xFFFFL;
    long SRC_PLANE_MASK = 0x3L;
    long SRC_ROTATION_MASK = 0x3L;

    /**
     * The scene nobody modelled as an instance: not an instance, no grid, no
     * chunks. This is what {@link GameSnapshot#dynamicRegion()} answers by
     * default, and it is the honest answer rather than a convenient one —
     * {@link #sourceOf(int, int, int)} on it is empty for every tile, which is
     * exactly right for a static scene.
     *
     * <p>An immutable constant, not a service instance.</p>
     */
    DynamicRegion STATIC = new StaticDynamicRegion();

    // ------------------------------------------------------------------
    // Scalars (the published header)
    // ------------------------------------------------------------------

    /** Whether the scene is a dynamic region. When {@code false} the grid is empty
     *  and every {@link #sourceOf(int, int, int)} is empty. */
    boolean isInstance();

    /** Whether the client's grid exceeded the wire cap and was dropped. The chunk
     *  array is then empty, but {@link #gridW()}, {@link #gridH()} and
     *  {@link #requiredChunks()} stay populated so the overflow is diagnosable —
     *  a truncated region is indistinguishable from a static one on
     *  {@link #chunkCount()} alone, which is why this flag exists. */
    boolean isTruncated();

    /**
     * The client's scene mode: {@link #SCENE_MODE_STATIC} for a static scene,
     * {@code 4..7} for the dynamic size classes.
     *
     * <p><b>Diagnostic only — {@link #isInstance()} is the predicate.</b> The two
     * are independent fields on the client and can disagree: a never-written
     * snapshot buffer reports mode {@code 0}, which is no mode at all, and a
     * scene caught mid-rebuild can report a dynamic mode with no descriptor yet
     * installed. The producer branches on the descriptor pointer, exactly as the
     * engine does, and so should you. Use this for logging and for telling the
     * size classes apart, never as the instance test.</p>
     */
    int sceneMode();

    /** Minimum loaded MAPSQUARE X — the grid's origin. Mapsquares, not chunks. */
    int originMapX();

    /** Minimum loaded MAPSQUARE Y — the grid's origin. Mapsquares, not chunks. */
    int originMapY();

    /** Maximum loaded MAPSQUARE X. Bounds the client's loaded window, <b>not</b> the
     *  resolvable area — see the class javadoc. */
    int maxMapX();

    /** Maximum loaded MAPSQUARE Y. Bounds the client's loaded window, <b>not</b> the
     *  resolvable area — see the class javadoc. */
    int maxMapY();

    /** Descriptor grid width in CHUNKS ({@code 0} when static). */
    int gridW();

    /** Descriptor grid height in CHUNKS ({@code 0} when static). */
    int gridH();

    /** {@code 4 * gridW * gridH} as the producer computed it. Always written, so
     *  comparing it against {@link #chunkCount()} tells you how badly a
     *  {@link #isTruncated()} grid overflowed. */
    int requiredChunks();

    /** Number of descriptors actually published, {@code 0} when static or truncated.
     *  Indices {@code [0, chunkCount)} are the only ones safe to read: the producer
     *  leaves the rest of the array stale rather than clearing it every tick. */
    int chunkCount();

    /**
     * The raw packed descriptor at flat index {@code index}.
     *
     * <p>The grid is <b>plane-major</b>: cell {@code (gx, gy)} on plane {@code p}
     * is at {@code ((p * gridW()) + gx) * gridH() + gy}. Prefer
     * {@link #chunkDescriptorAt(int, int, int)}, which does that arithmetic and
     * the mapsquare-to-chunk conversion for you.</p>
     *
     * @throws IndexOutOfBoundsException if {@code index} is outside {@code [0, chunkCount())}
     */
    int chunkAt(int index);

    // ------------------------------------------------------------------
    // Resolver
    // ------------------------------------------------------------------

    /**
     * The raw packed descriptor covering world tile {@code (tileX, tileY)} on
     * {@code plane}, or {@link #NO_CHUNK} if that tile has no source chunk.
     *
     * <p>This is where the UNITS TRAP is paid off exactly once: the tile is
     * reduced to a chunk ({@code >> 3}) and the mapsquare origin is promoted to
     * chunks ({@code << 3}) before they are subtracted.</p>
     *
     * <p>Returns {@link #NO_CHUNK} rather than throwing for every out-of-range
     * case — not an instance, plane out of range, tile outside the grid, index
     * past the published count. "No source" is a normal answer here.</p>
     */
    default int chunkDescriptorAt(int tileX, int tileY, int plane) {
        if (!isInstance() || plane < 0 || plane >= PLANE_COUNT) {
            return NO_CHUNK;
        }
        int gridX = (tileX >> CHUNK_SHIFT) - (originMapX() << MAPSQUARE_CHUNK_SHIFT);
        int gridY = (tileY >> CHUNK_SHIFT) - (originMapY() << MAPSQUARE_CHUNK_SHIFT);
        int width = gridW();
        int height = gridH();
        if (gridX < 0 || gridX >= width || gridY < 0 || gridY >= height) {
            return NO_CHUNK;
        }
        int index = ((plane * width) + gridX) * height + gridY;
        if (index < 0 || index >= chunkCount()) {
            return NO_CHUNK;
        }
        return chunkAt(index);
    }

    /**
     * {@link #sourceOf(int, int, int)} without the allocation: the source tile
     * packed into a {@code long}, or {@link #NO_SOURCE}.
     *
     * <p>This is the hot path — a per-tile loop rebuilding an instance's
     * collision runs this once per tile and must not generate garbage. Unpack
     * with {@link #srcTileX(long)} / {@link #srcTileY(long)} /
     * {@link #srcPlane(long)} / {@link #srcRotation(long)}.</p>
     *
     * <p><b>Hoist {@link GameSnapshot#dynamicRegion()} out of the loop.</b> This
     * call allocates nothing, but <i>acquiring</i> the region is not free, and
     * writing {@code snapshot.dynamicRegion().sourceOfPacked(...)} inside the
     * loop puts the acquisition back on the per-tile path and throws away the
     * reason this method exists:</p>
     * <pre>{@code
     *   DynamicRegion region = snapshot.dynamicRegion();   // once
     *   for (int tileY = y0; tileY <= y1; tileY++) {
     *       for (int tileX = x0; tileX <= x1; tileX++) {
     *           long src = region.sourceOfPacked(tileX, tileY, plane);
     *           if (src != NO_SOURCE) {
     *               bake(DynamicRegion.srcTileX(src), DynamicRegion.srcTileY(src));
     *           }
     *       }
     *   }
     * }</pre>
     */
    default long sourceOfPacked(int tileX, int tileY, int plane) {
        int descriptor = chunkDescriptorAt(tileX, tileY, plane);
        if (isHole(descriptor)) {
            return NO_SOURCE;
        }
        int rotation = descRotation(descriptor);
        int localX = tileX & CHUNK_TILE_MASK;
        int localY = tileY & CHUNK_TILE_MASK;
        int sourceX = (descChunkX(descriptor) << CHUNK_SHIFT)
                + rotateLocalX(localX, localY, rotation);
        int sourceY = (descChunkY(descriptor) << CHUNK_SHIFT)
                + rotateLocalY(localX, localY, rotation);
        return packSource(sourceX, sourceY, descPlane(descriptor), rotation);
    }

    /**
     * The static-map tile that world tile {@code (tileX, tileY, plane)} was
     * copied from, or empty if this tile has no source — because the scene is
     * static, because the tile is outside the descriptor grid, or because the
     * instance has a hole there.
     *
     * <p>Empty is a routine answer, not an error. In particular a tile can sit
     * comfortably inside the client's loaded window and still have no source
     * (see the class javadoc).</p>
     */
    default Optional<SourceTile> sourceOf(int tileX, int tileY, int plane) {
        long packed = sourceOfPacked(tileX, tileY, plane);
        if (packed == NO_SOURCE) {
            return Optional.empty();
        }
        return Optional.of(new SourceTile(
                srcTileX(packed), srcTileY(packed), srcPlane(packed), srcRotation(packed)));
    }

    // ------------------------------------------------------------------
    // Descriptor decode
    // ------------------------------------------------------------------

    /** Whether {@code descriptor} means "no source chunk". Tests {@code < 0} rather
     *  than {@code == NO_CHUNK} so a malformed high-bit value degrades to "no
     *  source" instead of decoding into garbage coordinates. */
    static boolean isHole(int descriptor) {
        return descriptor < 0;
    }

    /** Source plane, {@code 0..3}. Meaningless on a hole. */
    static int descPlane(int descriptor) {
        return (descriptor >>> DESC_PLANE_SHIFT) & DESC_PLANE_MASK;
    }

    /** Source chunk X, {@code 0..1023}. Multiply by {@link #CHUNK_TILES} for tiles,
     *  or {@code >> 3} for the source mapsquare. */
    static int descChunkX(int descriptor) {
        return (descriptor >>> DESC_CHUNK_X_SHIFT) & DESC_CHUNK_X_MASK;
    }

    /** Source chunk Y, {@code 0..2047}. */
    static int descChunkY(int descriptor) {
        return (descriptor >>> DESC_CHUNK_Y_SHIFT) & DESC_CHUNK_Y_MASK;
    }

    /** Chunk rotation, {@code 0..3} in 90-degree steps. Never observed non-zero
     *  live — see the class javadoc. */
    static int descRotation(int descriptor) {
        return (descriptor >>> DESC_ROTATION_SHIFT) & DESC_ROTATION_MASK;
    }

    // ------------------------------------------------------------------
    // Rotation
    // ------------------------------------------------------------------

    /**
     * Source-local X for destination-local {@code (localX, localY)} within an 8x8
     * chunk rotated by {@code rotation}.
     *
     * <p>Transcribed from the client's own rotation switch:
     * {@code r0 (x,y)}, {@code r1 (y, 7-x)}, {@code r2 (7-x, 7-y)},
     * {@code r3 (7-y, x)}. Only the low two bits of {@code rotation} are used,
     * matching the 2-bit wire field.</p>
     */
    static int rotateLocalX(int localX, int localY, int rotation) {
        return switch (rotation & ROTATION_MASK) {
            case 1 -> localY;
            case 2 -> CHUNK_MAX_LOCAL - localX;
            case 3 -> CHUNK_MAX_LOCAL - localY;
            default -> localX;
        };
    }

    /** Source-local Y counterpart of {@link #rotateLocalX(int, int, int)}. */
    static int rotateLocalY(int localX, int localY, int rotation) {
        return switch (rotation & ROTATION_MASK) {
            case 1 -> CHUNK_MAX_LOCAL - localX;
            case 2 -> CHUNK_MAX_LOCAL - localY;
            case 3 -> localX;
            default -> localY;
        };
    }

    /**
     * A {@link Location}'s rotation as it appears in the instance, given its
     * rotation in the source region and the chunk's rotation: the two compose
     * additively mod 4.
     *
     * <p>Directional collision bits rotate by the same amount. Reading a source
     * tile's wall flags and applying them unrotated is the standard way to get
     * an instance's collision subtly wrong.</p>
     */
    static int rotateLocRotation(int locRotation, int chunkRotation) {
        return (locRotation + chunkRotation) & ROTATION_MASK;
    }

    // ------------------------------------------------------------------
    // Packed source-tile codec
    // ------------------------------------------------------------------

    /** Packs a resolved source tile the way {@link #sourceOfPacked(int, int, int)}
     *  returns it. */
    static long packSource(int tileX, int tileY, int plane, int rotation) {
        return ((tileX & SRC_TILE_MASK) << SRC_TILE_X_SHIFT)
                | ((tileY & SRC_TILE_MASK) << SRC_TILE_Y_SHIFT)
                | ((plane & SRC_PLANE_MASK) << SRC_PLANE_SHIFT)
                | ((rotation & SRC_ROTATION_MASK) << SRC_ROTATION_SHIFT);
    }

    /** Source tile X out of a {@link #sourceOfPacked(int, int, int)} result. */
    static int srcTileX(long packed) {
        return (int) ((packed >>> SRC_TILE_X_SHIFT) & SRC_TILE_MASK);
    }

    /** Source tile Y out of a {@link #sourceOfPacked(int, int, int)} result. */
    static int srcTileY(long packed) {
        return (int) ((packed >>> SRC_TILE_Y_SHIFT) & SRC_TILE_MASK);
    }

    /** Source plane out of a {@link #sourceOfPacked(int, int, int)} result. */
    static int srcPlane(long packed) {
        return (int) ((packed >>> SRC_PLANE_SHIFT) & SRC_PLANE_MASK);
    }

    /** Chunk rotation out of a {@link #sourceOfPacked(int, int, int)} result. */
    static int srcRotation(long packed) {
        return (int) ((packed >>> SRC_ROTATION_SHIFT) & SRC_ROTATION_MASK);
    }

    // ------------------------------------------------------------------
    // Retaining a region past its tick
    // ------------------------------------------------------------------

    /**
     * A heap copy of {@code source}, safe to retain across ticks.
     *
     * <p>The snapshot-backed implementation is a flyweight over shared memory
     * and dies with its tick. Rebuilding instanced collision is a
     * multi-tick job, so it starts by copying the grid once here and keying the
     * result on {@link GameSnapshot#sceneVersion()} — when that changes, throw
     * the copy away and take a new one.</p>
     *
     * <p>Lossless: every scalar survives, including on a static or truncated
     * region, so a copy is always a faithful record of what the producer
     * published.</p>
     *
     * <p><b>Not torn-read protected.</b> Every other snapshot accessor reads a
     * handful of bytes; this one reads up to 64 KB out of a live, double-buffered
     * mapping that the producer may overwrite mid-copy, and the region carries no
     * seqlock to notice. The copy can therefore straddle two publishes and mix
     * chunks from two different scenes — which for this data is worse than
     * useless, because a half-stale grid resolves to plausible-looking wrong
     * tiles rather than failing. The caller's obligation is to re-read
     * {@link GameSnapshot#publishSeq()} after copying and retry if it moved.
     * {@link #copyOfStable(GameSnapshot)} does exactly that, and is what you
     * should call unless you have a reason not to.</p>
     */
    static DynamicRegion copyOf(DynamicRegion source) {
        return new ArrayDynamicRegion(source);
    }

    /** Copy attempts {@link #copyOfStable(GameSnapshot)} makes before giving up.
     *  A copy takes microseconds against a ~20ms republish cadence, so losing
     *  three races in a row means something is wrong, not unlucky. */
    int STABLE_COPY_ATTEMPTS = 3;

    /**
     * {@link #copyOf(DynamicRegion)} with the torn-read check built in: copies
     * the grid, re-reads {@link GameSnapshot#publishSeq()}, and retries if the
     * producer republished while the copy was in flight.
     *
     * <p>Returns empty if it lost the race {@value #STABLE_COPY_ATTEMPTS} times.
     * Empty means "ask again next tick" — it does <b>not</b> mean the scene is
     * static, and substituting {@link #STATIC} for it would be exactly the silent
     * wrong answer this method exists to prevent.</p>
     *
     * <p>Snapshot stubs whose {@code publishSeq()} never moves always succeed on
     * the first attempt, so this is safe to call from test code.</p>
     */
    static Optional<DynamicRegion> copyOfStable(GameSnapshot snapshot) {
        for (int attempt = 0; attempt < STABLE_COPY_ATTEMPTS; attempt++) {
            long before = snapshot.publishSeq();
            DynamicRegion copy = copyOf(snapshot.dynamicRegion());
            if (snapshot.publishSeq() == before) {
                return Optional.of(copy);
            }
        }
        return Optional.empty();
    }
}
