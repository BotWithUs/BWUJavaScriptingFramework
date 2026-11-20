package com.botwithus.bot.core.worldwalker;

import com.botwithus.bot.api.model.RouteLink;
import com.botwithus.bot.api.world.Coordinate;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code WwPlanOptions} and {@code WwRouteLink} against the offsets {@code worldwalker_c.h}
 * pins with {@code static_assert} at WorldWalker master da2eceb1.
 */
class PlanOptionsTest {

    @Test
    void layouts_matchTheHeaderSizes() {
        assertAll(
                () -> assertEquals(48, PlanOptions.WW_PLAN_OPTIONS.byteSize()),
                () -> assertEquals(PlanOptions.SIZE_V2, PlanOptions.WW_PLAN_OPTIONS.byteSize()),
                () -> assertEquals(PlanOptions.SIZE_V1, PlanOptions.AVOID_AREAS_OFFSET),
                () -> assertEquals(20, PlanOptions.WW_AREA.byteSize()),
                () -> assertEquals(PlanOptions.AVOID_AREA_COUNT_OFFSET,
                        PlanOptions.WW_PLAN_OPTIONS.byteOffset(
                                MemoryLayout.PathElement.groupElement("avoidAreaCount"))));
    }

    @Test
    void noAreas_writesV1Size_soAnOlderLibraryAcceptsIt() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = PlanOptions.write(arena, new DisabledMoves(0x21), List.of());

            assertAll(
                    () -> assertEquals(32, seg.get(JAVA_INT, PlanOptions.STRUCT_SIZE_OFFSET)),
                    () -> assertEquals(0x21, seg.get(JAVA_INT, PlanOptions.DISABLED_MOVES_OFFSET)),
                    () -> assertEquals(0, seg.get(JAVA_INT, PlanOptions.FLAGS_OFFSET)),
                    () -> assertEquals(0L, seg.get(ADDRESS, PlanOptions.EXCLUDED_OFFSET).address()),
                    () -> assertEquals(0L, seg.get(JAVA_LONG, PlanOptions.EXCLUDED_COUNT_OFFSET)),
                    () -> assertEquals(0L, seg.get(ADDRESS, PlanOptions.AVOID_AREAS_OFFSET).address()),
                    () -> assertEquals(0L, seg.get(JAVA_LONG, PlanOptions.AVOID_AREA_COUNT_OFFSET)));
        }
    }

    @Test
    void areas_writeV2Size_andEveryAreaFieldInOrder() {
        try (Arena arena = Arena.ofConfined()) {
            List<WwArea> areas = List.of(new WwArea(0, 3200, 3210, 3215, 3225),
                    new WwArea(WwArea.ALL_PLANES, 2900, 3300, 2901, 3302));

            MemorySegment seg = PlanOptions.write(arena, DisabledMoves.NONE, areas);

            MemorySegment buf = seg.get(ADDRESS, PlanOptions.AVOID_AREAS_OFFSET).reinterpret(40);
            assertAll(
                    () -> assertEquals(48, seg.get(JAVA_INT, PlanOptions.STRUCT_SIZE_OFFSET)),
                    () -> assertEquals(2L, seg.get(JAVA_LONG, PlanOptions.AVOID_AREA_COUNT_OFFSET)),
                    () -> assertEquals(0, buf.get(JAVA_INT, 0)),
                    () -> assertEquals(3200, buf.get(JAVA_INT, 4)),
                    () -> assertEquals(3210, buf.get(JAVA_INT, 8)),
                    () -> assertEquals(3215, buf.get(JAVA_INT, 12)),
                    () -> assertEquals(3225, buf.get(JAVA_INT, 16)),
                    () -> assertEquals(-1, buf.get(JAVA_INT, 20)),
                    () -> assertEquals(2900, buf.get(JAVA_INT, 24)),
                    () -> assertEquals(3302, buf.get(JAVA_INT, 36)));
        }
    }

    @Test
    void overSixtyFourAreas_isRefused() {
        List<WwArea> areas = new ArrayList<>();
        for (int i = 0; i <= PlanOptions.MAX_AVOID_AREAS; i++) {
            areas.add(new WwArea(0, i, i, i, i));
        }
        try (Arena arena = Arena.ofConfined()) {
            assertThrows(IllegalArgumentException.class,
                    () -> PlanOptions.write(arena, DisabledMoves.NONE, areas));
        }
    }

    @Test
    void routeLink_decodesEveryField() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rec = arena.allocate(PlanOptions.ROUTE_LINK_BYTES * 2);
            MemorySegment second = rec.asSlice(PlanOptions.ROUTE_LINK_BYTES, PlanOptions.ROUTE_LINK_BYTES);
            second.set(JAVA_INT, 0, 7);
            second.set(JAVA_INT, 4, 0xFFFF_FFF0);
            second.set(JAVA_INT, 8, 1);
            second.set(JAVA_INT, 12, 7);
            second.set(JAVA_INT, 16, 0);
            second.set(JAVA_INT, 20, 12128);
            int[] tiles = {3129, 3496, 0, 2412, 4434, 0};
            for (int i = 0; i < tiles.length; i++) {
                second.set(JAVA_INT, 24 + 4L * i, tiles[i]);
            }
            MemorySegment.copy(MemorySegment.ofArray("CKS".getBytes(StandardCharsets.UTF_8)), 0,
                    second, PlanOptions.RL_CODE, 3);
            byte[] name = "Zanaris".getBytes(StandardCharsets.UTF_8);
            MemorySegment.copy(MemorySegment.ofArray(name), 0, second, PlanOptions.RL_NAME, name.length);

            List<RouteLink> links = PlanOptions.readLinks(rec, 2);

            RouteLink l = links.get(1);
            assertAll(
                    () -> assertEquals(7, l.stepIndex()),
                    () -> assertEquals(0xFFFF_FFF0L, l.transitionIndex(), "u32, not sign-extended"),
                    () -> assertEquals(RouteLink.Kind.FAIRY_RING, l.kind()),
                    () -> assertEquals(7, l.moveCategory()),
                    () -> assertEquals(RouteLink.Origin.LOC, l.origin()),
                    () -> assertEquals(12128, l.objectId()),
                    () -> assertEquals(new Coordinate(3129, 3496, 0), l.from()),
                    () -> assertEquals(new Coordinate(2412, 4434, 0), l.to()),
                    () -> assertEquals("CKS", l.code()),
                    () -> assertEquals("Zanaris", l.name()),
                    () -> assertEquals("", links.get(0).code()));
        }
    }

    @Test
    void routeLink_unterminatedName_usesAllSixtyFourBytes() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rec = arena.allocate(PlanOptions.ROUTE_LINK_BYTES);
            rec.asSlice(PlanOptions.RL_NAME, PlanOptions.ROUTE_LINK_NAME_CAP).fill((byte) 'a');

            assertEquals(64, PlanOptions.readLink(rec).name().length());
        }
    }

    @Test
    void zeroLinks_withNullArray_isEmpty() {
        assertEquals(List.of(), PlanOptions.readLinks(MemorySegment.NULL, 0));
    }
}
