package com.botwithus.bot.core.worldwalker;

import com.botwithus.bot.api.model.RouteLink;
import com.botwithus.bot.api.world.Coordinate;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The {@code WwPlanOptions} writer and the {@code WwRouteLink} reader, mirroring
 * {@code worldwalker_c.h} byte for byte. Holds no native handle, so both are testable
 * without the library.
 *
 * <p><b>Size rule.</b> A library that predates avoid areas refuses a 48-byte
 * {@code WwPlanOptions} even with no areas in it, so the struct is written at
 * {@link #SIZE_V1} whenever there are no areas, and at {@link #SIZE_V2} only when there
 * are.</p>
 */
final class PlanOptions {

    /** {@code WW_PLAN_OPTIONS_SIZE_V1}: up to {@code excludedTransitionCount}. */
    static final int SIZE_V1 = 32;
    /** {@code WW_PLAN_OPTIONS_SIZE_V2}: adds {@code avoidAreas} / {@code avoidAreaCount}. */
    static final int SIZE_V2 = 48;
    /** {@code WW_MAX_AVOID_AREAS}. */
    static final int MAX_AVOID_AREAS = 64;
    /** {@code WW_ROUTE_LINK_NAME_CAP}, terminating NUL included. */
    static final int ROUTE_LINK_NAME_CAP = 64;
    /** Size of {@code WwRouteLink.code}. */
    static final int ROUTE_LINK_CODE_BYTES = 4;
    /** Ceiling on a native-reported link count (one per transition step, so the step cap). */
    static final int MAX_ROUTE_LINKS = WorldWalkerLayouts.MAX_PATH_STEPS;

    static final long STRUCT_SIZE_OFFSET = 0;
    static final long DISABLED_MOVES_OFFSET = 4;
    static final long FLAGS_OFFSET = 8;
    static final long EXCLUDED_OFFSET = 16;
    static final long EXCLUDED_COUNT_OFFSET = 24;
    static final long AVOID_AREAS_OFFSET = 32;
    static final long AVOID_AREA_COUNT_OFFSET = 40;

    /** {@code WwPlanOptions} at its V2 size: 4 x u32, then two (pointer, size_t) pairs. */
    static final StructLayout WW_PLAN_OPTIONS = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("structSize"),
            ValueLayout.JAVA_INT.withName("disabledMoves"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_INT.withName("pad"),
            ADDRESS.withName("excludedTransitions"),
            JAVA_LONG.withName("excludedTransitionCount"),
            ADDRESS.withName("avoidAreas"),
            JAVA_LONG.withName("avoidAreaCount"));

    /** {@code WwArea { i32 plane, minX, minY, maxX, maxY; }}: 20 bytes. */
    static final StructLayout WW_AREA = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("plane"),
            ValueLayout.JAVA_INT.withName("minX"),
            ValueLayout.JAVA_INT.withName("minY"),
            ValueLayout.JAVA_INT.withName("maxX"),
            ValueLayout.JAVA_INT.withName("maxY"));

    /** {@code sizeof(WwRouteLink)}. */
    static final long ROUTE_LINK_BYTES = 116;
    static final long RL_STEP_INDEX = 0;
    static final long RL_TRANSITION_INDEX = 4;
    static final long RL_KIND = 8;
    static final long RL_MOVE_CATEGORY = 12;
    static final long RL_ORIGIN = 16;
    static final long RL_OBJECT_ID = 20;
    static final long RL_FROM_TILE = 24;
    static final long RL_TO_TILE = 36;
    static final long RL_CODE = 48;
    static final long RL_NAME = 52;
    private static final long TILE_Y = 4;
    private static final long TILE_PLANE = 8;

    private PlanOptions() {
    }

    /**
     * Writes a zeroed {@code WwPlanOptions} carrying {@code moves} and {@code areas}, sized
     * {@link #SIZE_V1} when {@code areas} is empty and {@link #SIZE_V2} otherwise.
     *
     * @throws IllegalArgumentException past {@link #MAX_AVOID_AREAS} areas
     */
    static MemorySegment write(SegmentAllocator allocator, DisabledMoves moves, List<WwArea> areas) {
        if (areas.size() > MAX_AVOID_AREAS) {
            throw new IllegalArgumentException("at most " + MAX_AVOID_AREAS + " avoid areas, got "
                    + areas.size());
        }
        MemorySegment seg = allocator.allocate(WW_PLAN_OPTIONS);
        seg.fill((byte) 0);
        seg.set(JAVA_INT, STRUCT_SIZE_OFFSET, areas.isEmpty() ? SIZE_V1 : SIZE_V2);
        seg.set(JAVA_INT, DISABLED_MOVES_OFFSET, moves.mask());
        seg.set(ADDRESS, AVOID_AREAS_OFFSET, writeAreas(allocator, areas));
        seg.set(JAVA_LONG, AVOID_AREA_COUNT_OFFSET, areas.size());
        return seg;
    }

    private static MemorySegment writeAreas(SegmentAllocator allocator, List<WwArea> areas) {
        if (areas.isEmpty()) {
            return MemorySegment.NULL;
        }
        MemorySegment buf = allocator.allocate(WW_AREA, areas.size());
        long stride = WW_AREA.byteSize();
        for (int i = 0; i < areas.size(); i++) {
            WwArea a = areas.get(i);
            int[] fields = {a.plane(), a.minX(), a.minY(), a.maxX(), a.maxY()};
            for (int f = 0; f < fields.length; f++) {
                buf.set(JAVA_INT, i * stride + (long) f * Integer.BYTES, fields[f]);
            }
        }
        return buf;
    }

    /** Decodes {@code count} {@code WwRouteLink}s from {@code links}. */
    static List<RouteLink> readLinks(MemorySegment links, long count) {
        int n = WorldWalkerLayouts.boundedCount(count, MAX_ROUTE_LINKS, "ww_path_links count");
        if (n == 0) {
            return List.of();
        }
        if (links.address() == 0L) {
            throw new WorldWalkerException("ww_path_links returned count=" + n + " with a null array");
        }
        MemorySegment view = links.reinterpret(n * ROUTE_LINK_BYTES);
        List<RouteLink> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(readLink(view.asSlice(i * ROUTE_LINK_BYTES, ROUTE_LINK_BYTES)));
        }
        return out;
    }

    static RouteLink readLink(MemorySegment link) {
        return new RouteLink(
                link.get(JAVA_INT, RL_STEP_INDEX),
                Integer.toUnsignedLong(link.get(JAVA_INT, RL_TRANSITION_INDEX)),
                link.get(JAVA_INT, RL_KIND),
                link.get(JAVA_INT, RL_MOVE_CATEGORY),
                link.get(JAVA_INT, RL_ORIGIN),
                link.get(JAVA_INT, RL_OBJECT_ID),
                readTile(link, RL_FROM_TILE),
                readTile(link, RL_TO_TILE),
                readCString(link, RL_CODE, ROUTE_LINK_CODE_BYTES),
                readCString(link, RL_NAME, ROUTE_LINK_NAME_CAP));
    }

    private static Coordinate readTile(MemorySegment link, long offset) {
        return new Coordinate(link.get(JAVA_INT, offset), link.get(JAVA_INT, offset + TILE_Y),
                link.get(JAVA_INT, offset + TILE_PLANE));
    }

    /** A NUL-padded UTF-8 field of {@code cap} bytes; a field with no NUL uses all of them. */
    private static String readCString(MemorySegment seg, long offset, int cap) {
        int len = 0;
        while (len < cap && seg.get(JAVA_BYTE, offset + len) != 0) {
            len++;
        }
        byte[] bytes = seg.asSlice(offset, len).toArray(JAVA_BYTE);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
