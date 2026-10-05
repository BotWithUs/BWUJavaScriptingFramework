package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.draw.Colors;
import com.botwithus.bot.api.draw.DrawCaption;
import com.botwithus.bot.api.draw.DrawCommand;
import com.botwithus.bot.api.draw.DrawLimits;
import com.botwithus.bot.api.draw.DrawSpace;
import com.botwithus.bot.api.draw.DrawStyle;
import com.botwithus.bot.core.worldwalker.StepKind;
import com.botwithus.bot.core.worldwalker.WwStep;
import com.botwithus.bot.core.worldwalker.WwTile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns one walk's planned route into the draw commands of the WorldWalker path overlay.
 * Pure: no I/O, no state beyond the walk's key prefix, so every rule here is testable
 * without a game.
 *
 * <p><b>Points and segments.</b> A route is its start tile followed by each step's target
 * tile, so segment {@code k} runs from point {@code k} to step {@code k}'s target and
 * leads <i>into</i> step {@code k}. A walk at step {@code c} has passed every segment
 * before {@code c}, so those are not drawn.</p>
 *
 * <p><b>What is drawn.</b> A world-space line per segment, but only when both ends are on
 * the player's plane: a world primitive carries no plane and is drawn at the player's
 * ground height, so a segment on another floor would be drawn convincingly wrong. A
 * segment leaving a transition step is skipped when it is longer than
 * {@link #MAX_POST_TRANSITION_HOP_TILES}: the transition moved the player somewhere the
 * plan does not record (a teleport lands far from where it was cast), and a line from the
 * cast tile would cross the map. Transition steps and the current click target get a tile
 * highlight, again only on the player's plane, because a highlight on another plane is
 * stored by the producer and draws nothing.</p>
 *
 * <p><b>Budget.</b> At most {@link #MAX_SEGMENTS} segments ahead of the current step are
 * considered, and at most {@link #MAX_TRANSITION_MARKERS} transitions are marked. Lines
 * ride one batch; each highlight is its own round-trip, which is why the markers are
 * capped much lower than the lines. The whole overlay stays far inside
 * {@link DrawLimits#MAX_COMMANDS}, which is shared with the scripts on the connection.</p>
 *
 * <p><b>Keys.</b> Every key starts {@code ww:<walkId>:}. The {@code ww:} root marks the
 * overlay's keys; the walk id keeps two walks apart, so a walk that is wound down while
 * its successor draws can only clear its own keys. Segment and marker keys are numbered
 * by slot in the drawn window, not by step, so the key set stays bounded however long the
 * route is.</p>
 */
final class WalkPathLayout {

    /** Root of every key the overlay uses. */
    static final String KEY_ROOT = "ww:";

    /** Most segments considered ahead of the current step. */
    static final int MAX_SEGMENTS = 120;

    /** Most transition steps marked ahead of the current step; each is one round-trip. */
    static final int MAX_TRANSITION_MARKERS = 4;

    /** Longest segment drawn out of a transition step, in tiles (Chebyshev). */
    static final int MAX_POST_TRANSITION_HOP_TILES = 32;

    /**
     * Lifetime of every overlay command. Redraws refresh it, so it only matters when the
     * host stops redrawing without clearing - a crash - and then the path fades by itself.
     */
    static final long TTL_MS = 30_000L;

    private static final String SEGMENT_KEY = "seg:";
    private static final String TRANSITION_KEY = "tr:";
    private static final String TARGET_KEY = "target";

    /** Sub-tile offset of a tile's centre, so a line runs centre to centre. */
    private static final int TILE_CENTRE = DrawLimits.SUBTILE_SCALE / 2;

    private static final int LINE_THICKNESS = 2;
    private static final int MARKER_THICKNESS = 2;
    private static final int LINE_Z = 0;
    private static final int MARKER_Z = 1;
    private static final DrawStyle LINE_STYLE =
            new DrawStyle(Colors.CYAN, LINE_THICKNESS, false, false, LINE_Z, TTL_MS);
    private static final DrawStyle TRANSITION_STYLE =
            new DrawStyle(Colors.ORANGE, MARKER_THICKNESS, false, false, MARKER_Z, TTL_MS);
    private static final DrawStyle TARGET_STYLE =
            new DrawStyle(Colors.YELLOW, MARKER_THICKNESS, false, false, MARKER_Z, TTL_MS);

    private final String prefix;

    /** Layout for the walk numbered {@code walkId}; the id goes into every key. */
    WalkPathLayout(long walkId) {
        this.prefix = KEY_ROOT + walkId + ':';
    }

    /**
     * A route as the planner returned it, anchored at the tile it was planned from.
     *
     * @param start the player's tile when the route was planned
     * @param steps the planned steps, in order
     */
    record PlannedPath(WwTile start, List<WwStep> steps) {

        PlannedPath {
            steps = List.copyOf(steps);
        }
    }

    /** One straight piece of the route, tile to tile. */
    record Segment(WwTile from, WwTile to) {
    }

    /** The prefix every key of this walk starts with. */
    String keyPrefix() {
        return prefix;
    }

    /** Key of the {@code slot}-th drawn segment. */
    String segmentKey(int slot) {
        return prefix + SEGMENT_KEY + slot;
    }

    /** Key of the {@code slot}-th transition marker. */
    String transitionKey(int slot) {
        return prefix + TRANSITION_KEY + slot;
    }

    /** Key of the click-target marker. */
    String targetKey() {
        return prefix + TARGET_KEY;
    }

    /**
     * Everything to draw for a walk at {@code currentStep} with the player on
     * {@code plane}: segment lines first, then transition markers, then the target marker.
     */
    List<DrawCommand> commands(Optional<PlannedPath> path, int currentStep, int plane,
                               Optional<WwTile> target) {
        List<DrawCommand> out = new ArrayList<>();
        path.ifPresent(p -> {
            addLines(out, segments(p, currentStep, plane));
            addTransitionMarkers(out, transitions(p, currentStep, plane));
        });
        target.filter(t -> t.plane() == plane)
                .ifPresent(t -> out.add(tileMarker(targetKey(), TARGET_STYLE, t)));
        return out;
    }

    /** The drawable segments in the window ahead of {@code currentStep}, in route order. */
    List<Segment> segments(PlannedPath path, int currentStep, int plane) {
        List<WwStep> steps = path.steps();
        int from = Math.max(0, currentStep);
        int to = Math.min(steps.size(), from + MAX_SEGMENTS);
        List<Segment> out = new ArrayList<>();
        for (int k = from; k < to; k++) {
            Segment segment = new Segment(pointBefore(path, k), tileOf(steps.get(k)));
            if (isDrawable(segment, plane) && !isTeleportHop(path, k, segment)) {
                out.add(segment);
            }
        }
        return out;
    }

    /** The transition steps to mark in the window ahead of {@code currentStep}. */
    List<WwStep> transitions(PlannedPath path, int currentStep, int plane) {
        List<WwStep> steps = path.steps();
        int from = Math.max(0, currentStep);
        int to = Math.min(steps.size(), from + MAX_SEGMENTS);
        List<WwStep> out = new ArrayList<>();
        for (int k = from; k < to && out.size() < MAX_TRANSITION_MARKERS; k++) {
            WwStep step = steps.get(k);
            if (step.kind() == StepKind.TRANSITION && step.plane() == plane) {
                out.add(step);
            }
        }
        return out;
    }

    /** Sub-tile world coordinate of the centre of {@code tile}. */
    static int centre(int tile) {
        return tile * DrawLimits.SUBTILE_SCALE + TILE_CENTRE;
    }

    private void addLines(List<DrawCommand> out, List<Segment> segments) {
        for (int slot = 0; slot < segments.size(); slot++) {
            Segment s = segments.get(slot);
            out.add(new DrawCommand.Line(segmentKey(slot), DrawSpace.WORLD, LINE_STYLE,
                    centre(s.from().x()), centre(s.from().y()),
                    centre(s.to().x()), centre(s.to().y())));
        }
    }

    private void addTransitionMarkers(List<DrawCommand> out, List<WwStep> transitions) {
        for (int slot = 0; slot < transitions.size(); slot++) {
            WwTile tile = tileOf(transitions.get(slot));
            out.add(tileMarker(transitionKey(slot), TRANSITION_STYLE, tile));
        }
    }

    private static DrawCommand tileMarker(String key, DrawStyle style, WwTile tile) {
        return new DrawCommand.Tile(key, style, tile.x(), tile.y(), tile.plane(), DrawCaption.NONE);
    }

    private static WwTile pointBefore(PlannedPath path, int stepIndex) {
        return stepIndex == 0 ? path.start() : tileOf(path.steps().get(stepIndex - 1));
    }

    private static WwTile tileOf(WwStep step) {
        return new WwTile(step.targetX(), step.targetY(), step.plane());
    }

    private static boolean isDrawable(Segment segment, int plane) {
        return segment.from().plane() == plane && segment.to().plane() == plane;
    }

    private static boolean isTeleportHop(PlannedPath path, int stepIndex, Segment segment) {
        if (stepIndex == 0 || path.steps().get(stepIndex - 1).kind() != StepKind.TRANSITION) {
            return false;
        }
        int dx = Math.abs(segment.to().x() - segment.from().x());
        int dy = Math.abs(segment.to().y() - segment.from().y());
        return Math.max(dx, dy) > MAX_POST_TRANSITION_HOP_TILES;
    }
}
