package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.draw.DrawCommand;
import com.botwithus.bot.api.draw.DrawKind;
import com.botwithus.bot.api.draw.DrawLimits;
import com.botwithus.bot.api.draw.DrawSpace;
import com.botwithus.bot.core.impl.WalkPathLayout.PlannedPath;
import com.botwithus.bot.core.impl.WalkPathLayout.Segment;
import com.botwithus.bot.core.worldwalker.StepKind;
import com.botwithus.bot.core.worldwalker.WwStep;
import com.botwithus.bot.core.worldwalker.WwTile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalkPathLayoutTest {

    private static final long WALK_ID = 7L;
    private static final int GROUND = 0;
    private static final int UPSTAIRS = 1;
    private static final int BASE_X = 3200;
    private static final int BASE_Y = 3200;
    private static final int LONG_ROUTE_STEPS = 200;
    private static final int SHORT_HOP = 3;
    private static final int TELEPORT_HOP = 500;

    private final WalkPathLayout layout = new WalkPathLayout(WALK_ID);

    @Test
    void keys_carryTheRootAndTheWalkId() {
        assertAll(
                () -> assertEquals("ww:7:", layout.keyPrefix()),
                () -> assertEquals("ww:7:seg:0", layout.segmentKey(0)),
                () -> assertEquals("ww:7:tr:3", layout.transitionKey(3)),
                () -> assertEquals("ww:7:target", layout.targetKey()));
    }

    @Test
    void keys_fitTheProducerCapForTheLargestWalkIdAndSlot() {
        WalkPathLayout widest = new WalkPathLayout(Long.MAX_VALUE);
        List<String> keys = List.of(widest.segmentKey(WalkPathLayout.MAX_SEGMENTS - 1),
                widest.transitionKey(WalkPathLayout.MAX_TRANSITION_MARKERS - 1),
                widest.targetKey());
        for (String key : keys) {
            assertTrue(key.getBytes(StandardCharsets.UTF_8).length <= DrawLimits.MAX_KEY_BYTES, key);
        }
    }

    @Test
    void segments_fromTheStart_runStartToFirstTargetThenTargetToTarget() {
        PlannedPath path = path(start(), walk(1, 0), walk(2, 0));

        List<Segment> segments = layout.segments(path, 0, GROUND);

        assertEquals(List.of(
                new Segment(start(), tile(1, 0, GROUND)),
                new Segment(tile(1, 0, GROUND), tile(2, 0, GROUND))), segments);
    }

    @Test
    void segments_dropTheOnesAlreadyPassed() {
        PlannedPath path = path(start(), walk(1, 0), walk(2, 0), walk(3, 0));

        List<Segment> segments = layout.segments(path, 2, GROUND);

        assertEquals(List.of(new Segment(tile(2, 0, GROUND), tile(3, 0, GROUND))), segments);
    }

    @Test
    void segments_aNegativeStepIsTreatedAsTheFirst() {
        PlannedPath path = path(start(), walk(1, 0));

        assertEquals(layout.segments(path, 0, GROUND), layout.segments(path, -1, GROUND));
    }

    @Test
    void segments_aStepPastTheEndDrawsNothing() {
        PlannedPath path = path(start(), walk(1, 0));

        assertTrue(layout.segments(path, LONG_ROUTE_STEPS, GROUND).isEmpty());
    }

    @Test
    void segments_onlyThoseWithBothEndsOnThePlayersPlane() {
        WwStep stairs = transition(1, 0, GROUND);
        PlannedPath path = path(start(), walk(1, 0), stairs, step(StepKind.WALK, 2, 0, UPSTAIRS),
                step(StepKind.WALK, 3, 0, UPSTAIRS));

        assertAll(
                () -> assertEquals(List.of(
                        new Segment(start(), tile(1, 0, GROUND)),
                        new Segment(tile(1, 0, GROUND), tile(1, 0, GROUND))),
                        layout.segments(path, 0, GROUND)),
                () -> assertEquals(List.of(new Segment(tile(2, 0, UPSTAIRS), tile(3, 0, UPSTAIRS))),
                        layout.segments(path, 0, UPSTAIRS)));
    }

    @Test
    void segments_considerOnlyTheWindowAheadOfTheCurrentStep() {
        PlannedPath path = longRoute();

        List<Segment> fromStart = layout.segments(path, 0, GROUND);
        List<Segment> nearEnd = layout.segments(path, LONG_ROUTE_STEPS - 1, GROUND);

        assertAll(
                () -> assertEquals(WalkPathLayout.MAX_SEGMENTS, fromStart.size()),
                () -> assertEquals(tile(WalkPathLayout.MAX_SEGMENTS, 0, GROUND),
                        fromStart.getLast().to()),
                () -> assertEquals(1, nearEnd.size()));
    }

    @Test
    void segments_skipALongHopOutOfATransition_butKeepAShortOne() {
        PlannedPath teleport = path(start(), transition(1, 0, GROUND), walk(TELEPORT_HOP, 0));
        PlannedPath door = path(start(), transition(1, 0, GROUND), walk(1 + SHORT_HOP, 0));

        assertAll(
                () -> assertEquals(List.of(new Segment(start(), tile(1, 0, GROUND))),
                        layout.segments(teleport, 0, GROUND)),
                () -> assertEquals(2, layout.segments(door, 0, GROUND).size()));
    }

    @Test
    void transitions_areOnTheCurrentPlane_aheadOfTheStep_andCapped() {
        List<WwStep> steps = new ArrayList<>();
        steps.add(transition(0, 1, UPSTAIRS));
        for (int i = 0; i <= WalkPathLayout.MAX_TRANSITION_MARKERS; i++) {
            steps.add(transition(i, 0, GROUND));
        }
        PlannedPath path = new PlannedPath(start(), steps);

        List<WwStep> marked = layout.transitions(path, 0, GROUND);
        List<WwStep> afterFirst = layout.transitions(path, 2, GROUND);

        assertAll(
                () -> assertEquals(WalkPathLayout.MAX_TRANSITION_MARKERS, marked.size()),
                () -> assertTrue(marked.stream().allMatch(s -> s.plane() == GROUND)),
                () -> assertEquals(steps.get(1), marked.getFirst()),
                () -> assertEquals(steps.get(2), afterFirst.getFirst()));
    }

    @Test
    void commands_areWorldLinesBetweenTileCentres_thenMarkers_allWithTheOverlayTtl() {
        PlannedPath path = path(start(), walk(1, 0), transition(2, 0, GROUND));

        List<DrawCommand> commands = layout.commands(Optional.of(path), 0, GROUND,
                Optional.of(tile(1, 0, GROUND)));

        assertAll(
                () -> assertEquals(List.of("ww:7:seg:0", "ww:7:seg:1", "ww:7:tr:0", "ww:7:target"),
                        commands.stream().map(DrawCommand::key).toList()),
                () -> assertEquals(List.of(DrawKind.LINE, DrawKind.LINE, DrawKind.TILE, DrawKind.TILE),
                        commands.stream().map(DrawCommand::kind).toList()),
                () -> assertTrue(commands.stream()
                        .allMatch(c -> c.style().ttlMs() == WalkPathLayout.TTL_MS)),
                () -> assertEquals(List.of(lineFrom(start(), tile(1, 0, GROUND))),
                        commands.subList(0, 1).stream().map(WalkPathLayoutTest::lineOf).toList()));
    }

    @Test
    void commands_leaveOutATargetOnAnotherPlane_andDrawOnlyTheTargetWithoutAPlan() {
        List<DrawCommand> upstairsTarget = layout.commands(Optional.empty(), 0, GROUND,
                Optional.of(tile(1, 0, UPSTAIRS)));
        List<DrawCommand> groundTarget = layout.commands(Optional.empty(), 0, GROUND,
                Optional.of(tile(1, 0, GROUND)));

        assertAll(
                () -> assertTrue(upstairsTarget.isEmpty()),
                () -> assertEquals(List.of("ww:7:target"),
                        groundTarget.stream().map(DrawCommand::key).toList()));
    }

    @Test
    void centre_isTheMiddleSubTileOfTheTile() {
        assertEquals(BASE_X * DrawLimits.SUBTILE_SCALE + DrawLimits.SUBTILE_SCALE / 2,
                WalkPathLayout.centre(BASE_X));
    }

    /** The geometry of a line command as {@code [space, x1, y1, x2, y2]}, or empty for others. */
    private static List<Object> lineOf(DrawCommand command) {
        return switch (command) {
            case DrawCommand.Line line -> List.of(line.space(), line.x1(), line.y1(),
                    line.x2(), line.y2());
            default -> List.of();
        };
    }

    private static List<Object> lineFrom(WwTile from, WwTile to) {
        return List.of(DrawSpace.WORLD, WalkPathLayout.centre(from.x()), WalkPathLayout.centre(from.y()),
                WalkPathLayout.centre(to.x()), WalkPathLayout.centre(to.y()));
    }

    private static PlannedPath longRoute() {
        List<WwStep> steps = new ArrayList<>();
        for (int i = 1; i <= LONG_ROUTE_STEPS; i++) {
            steps.add(walk(i, 0));
        }
        return new PlannedPath(start(), steps);
    }

    private static PlannedPath path(WwTile start, WwStep... steps) {
        return new PlannedPath(start, List.of(steps));
    }

    private static WwTile start() {
        return tile(0, 0, GROUND);
    }

    private static WwTile tile(int dx, int dy, int plane) {
        return new WwTile(BASE_X + dx, BASE_Y + dy, plane);
    }

    private static WwStep walk(int dx, int dy) {
        return step(StepKind.WALK, dx, dy, GROUND);
    }

    private static WwStep transition(int dx, int dy, int plane) {
        return step(StepKind.TRANSITION, dx, dy, plane);
    }

    private static WwStep step(StepKind kind, int dx, int dy, int plane) {
        long transitionIndex = kind == StepKind.WALK ? WwStep.WALK_TRANSITION_SENTINEL : 0L;
        return new WwStep(kind, plane, BASE_X + dx, BASE_Y + dy, transitionIndex);
    }
}
