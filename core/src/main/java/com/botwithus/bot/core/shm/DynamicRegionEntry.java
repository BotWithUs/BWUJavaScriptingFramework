package com.botwithus.bot.core.shm;

/**
 * The scalar header of the snapshot's dynamic-region block (v19+) — mirrors
 * {@code ipc::DynamicRegion} in NXTLibrary's SharedLayout.h field for field.
 *
 * <p>Read once per tick and cached by {@link DynamicRegionView}; the
 * descriptor grid itself stays in shared memory rather than being materialised
 * alongside these ten scalars.</p>
 *
 * <p>UNITS TRAP: {@code originMapX}/{@code originMapY}/{@code maxMapX}/{@code maxMapY}
 * are MAPSQUARES (64 tiles); {@code gridW}/{@code gridH} are CHUNKS (8 tiles).</p>
 *
 * @param isInstance     the scene is a dynamic region (the client's descriptor pointer was non-null)
 * @param isTruncated    the grid exceeded {@link Layout#DYN_CHUNK_CAP} and was dropped;
 *                       the dimensions below stay populated so the overflow is diagnosable
 * @param sceneMode      {@code 3} = static, {@code 4..7} = dynamic size classes
 * @param originMapX     minimum loaded mapsquare X — the grid's origin
 * @param originMapY     minimum loaded mapsquare Y — the grid's origin
 * @param maxMapX        maximum loaded mapsquare X; bounds the loaded window, not the grid
 * @param maxMapY        maximum loaded mapsquare Y; bounds the loaded window, not the grid
 * @param gridW          descriptor grid width in chunks ({@code 0} when static)
 * @param gridH          descriptor grid height in chunks ({@code 0} when static)
 * @param requiredChunks {@code 4 * gridW * gridH} as the producer computed it
 */
public record DynamicRegionEntry(
        boolean isInstance,
        boolean isTruncated,
        int sceneMode,
        int originMapX,
        int originMapY,
        int maxMapX,
        int maxMapY,
        int gridW,
        int gridH,
        int requiredChunks
) {
}
