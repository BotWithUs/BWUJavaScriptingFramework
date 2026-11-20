package com.botwithus.bot.core.cache;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED;

/**
 * One world map element placement from cache index 41, mirroring the C ABI
 * {@code nxt_wme_placement} (16 bytes).
 *
 * <p>Coordinates are in the <b>area's</b> map space. That is the world tile for nearly
 * every placement, but the main overview area draws a few regions (Prifddinas, Ashdale)
 * away from where they are, so keep {@link #areaId()} whenever world tiles matter.</p>
 *
 * @param areaId    the world map area (index-41 archive id)
 * @param elementId the WME definition id ({@code nxt_get_worldmap_json})
 * @param x         tile x in the area's map space
 * @param y         tile y in the area's map space
 * @param plane     0..3
 * @param flags     the record's raw trailing byte; meaning not pinned
 */
public record WmePlacement(int areaId, int elementId, int x, int y, int plane, int flags) {

    /** {@code sizeof(nxt_wme_placement)}. */
    static final long BYTES = 16;
    static final long AREA_ID_OFFSET = 0;
    static final long ELEMENT_ID_OFFSET = 4;
    static final long WORLD_X_OFFSET = 8;
    static final long WORLD_Y_OFFSET = 10;
    static final long PLANE_OFFSET = 12;
    static final long FLAGS_OFFSET = 13;
    /** Ceiling on a native-reported count; the whole index holds a few tens of thousands. */
    static final int MAX_PLACEMENTS = 1 << 20;

    /** Decodes {@code count} records from {@code array}, already sized to hold them. */
    static List<WmePlacement> readAll(MemorySegment array, int count) {
        List<WmePlacement> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(read(array.asSlice(i * BYTES, BYTES)));
        }
        return out;
    }

    static WmePlacement read(MemorySegment rec) {
        return new WmePlacement(
                rec.get(JAVA_INT_UNALIGNED, AREA_ID_OFFSET),
                rec.get(JAVA_INT_UNALIGNED, ELEMENT_ID_OFFSET),
                Short.toUnsignedInt(rec.get(JAVA_SHORT_UNALIGNED, WORLD_X_OFFSET)),
                Short.toUnsignedInt(rec.get(JAVA_SHORT_UNALIGNED, WORLD_Y_OFFSET)),
                Byte.toUnsignedInt(rec.get(JAVA_BYTE, PLANE_OFFSET)),
                Byte.toUnsignedInt(rec.get(JAVA_BYTE, FLAGS_OFFSET)));
    }
}
