package com.botwithus.bot.api.world;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.ScreenProjection;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.util.Waiter;

import java.util.List;
import java.util.Optional;

/**
 * The world list, the current world, and changing world. Obtain via {@code api.worlds()}.
 *
 * <pre>{@code
 * Worlds.HopResult hop = api.worlds().hop(84);
 * if (!hop.isArrived()) {
 *     log.info("hop to 84: {} {}", hop.outcome(), hop.reason());
 * }
 * }</pre>
 *
 * <p>A world change goes through the game's own UI, as a player does it: the agent clicks
 * through the world switcher (in the world) or the world-select pane (in the lobby) and
 * opens no connection itself. In the world the client reconnects on its own: game state
 * goes 30 → 37 → 30 over a few seconds. In the lobby the change only picks the world the
 * next login connects to.</p>
 */
public final class Worlds {

    /** Default wait for a hop to land, in ms: the agent gives up after 20 s on its side. */
    public static final long HOP_TIMEOUT_MS = 25_000L;
    /** {@link GameSnapshot#gameState()} in the lobby. */
    static final int GAME_STATE_LOBBY = 20;
    /** Wait for the transfer to leave game state 30 once the world number has changed, in ms. */
    private static final long TRANSFER_START_TIMEOUT_MS = 5_000L;
    private static final long NANOS_PER_MS = 1_000_000L;

    private final GameAPI api;
    private final Waiter waiter;

    public Worlds(GameAPI api) {
        this(api, Waiter.TIMING);
    }

    /** With {@code waiter} doing every wait, so a test can run the hop without sleeping. */
    public Worlds(GameAPI api, Waiter waiter) {
        this.api = api;
        this.waiter = waiter;
    }

    /** The current world number, {@code -1} when unknown or between worlds. */
    public int current() {
        return api.getCurrentWorld();
    }

    /** The client's world list; {@link WorldList#isFetched()} is false until the game fetched it. */
    public WorldList list() {
        return api.getWorldList();
    }

    /** The world numbered {@code id} in the client's list. */
    public Optional<World> find(int id) {
        return list().find(id);
    }

    /** {@link #hop(int, long)} with {@link #HOP_TIMEOUT_MS}. */
    public HopResult hop(int worldId) {
        return hop(worldId, HOP_TIMEOUT_MS);
    }

    /**
     * Changes to {@code worldId} and waits for it to land. Blocks the calling thread.
     *
     * <p>Arrival is {@code get_current_world} reporting the world and, when the hop started
     * in the world, the game state back at 30 after the transfer. From the lobby the world
     * number is the whole answer: nothing connects until the next login.</p>
     *
     * <p><b>Arrived is not scene-ready.</b> Game state is back at 30 a few hundred ms before
     * the new scene has loaded (measured live on 951-4); until then {@code world_to_screen}
     * answers {@code unavailable} and an action has nothing to anchor on. Call
     * {@link #awaitSceneReady(long)} before projecting or acting.</p>
     *
     * @return {@link HopOutcome#REFUSED} with the agent's reason when nothing was queued,
     *         {@link HopOutcome#ARRIVED} on arrival, {@link HopOutcome#TIMED_OUT} otherwise
     */
    public HopResult hop(int worldId, long timeoutMs) {
        boolean isFromLobby = gameState() == GAME_STATE_LOBBY;
        WorldHopRequest request = api.requestWorld(worldId);
        if (!request.started()) {
            return new HopResult(HopOutcome.REFUSED, request.reason());
        }
        long deadline = System.nanoTime() + msToNanos(timeoutMs);
        if (!waiter.until(() -> current() == worldId, timeoutMs)) {
            return new HopResult(HopOutcome.TIMED_OUT, "");
        }
        if (isFromLobby) {
            return new HopResult(HopOutcome.ARRIVED, "");
        }
        waiter.until(() -> gameState() != GameSnapshot.GAME_STATE_IN_GAME,
                Math.min(TRANSFER_START_TIMEOUT_MS, remainingMs(deadline)));
        boolean isBack = waiter.until(
                () -> gameState() == GameSnapshot.GAME_STATE_IN_GAME && current() == worldId,
                remainingMs(deadline));
        return new HopResult(isBack ? HopOutcome.ARRIVED : HopOutcome.TIMED_OUT, "");
    }

    /**
     * Waits until the scene can be projected into: the local player exists and its own tile
     * projects to anything but {@code unavailable}. One {@code world_to_screen} call per poll.
     * Use it after a {@link #hop}, whose arrival does not include the scene.
     *
     * @return {@code true} when the scene is ready, {@code false} at the timeout
     */
    public boolean awaitSceneReady(long timeoutMs) {
        return waiter.until(this::isSceneReady, timeoutMs);
    }

    private boolean isSceneReady() {
        GameSnapshot snapshot = api.snapshot();
        LocalPlayer self = snapshot == null ? null : snapshot.self();
        if (self == null) {
            return false;
        }
        Coordinate own = new Coordinate(self.tileX(), self.tileY(), self.plane());
        return api.worldToScreen(List.of(own)).points().stream()
                .anyMatch(p -> p.result() != ScreenProjection.Result.UNAVAILABLE);
    }

    private int gameState() {
        GameSnapshot snapshot = api.snapshot();
        return snapshot == null ? 0 : snapshot.gameState();
    }

    private static long msToNanos(long ms) {
        return Math.max(0L, ms) * NANOS_PER_MS;
    }

    private static long remainingMs(long deadlineNanos) {
        return Math.max(0L, (deadlineNanos - System.nanoTime()) / NANOS_PER_MS);
    }

    /** How a {@link #hop} ended. */
    public enum HopOutcome {
        /** On the world, and (from the world) back in game. */
        ARRIVED,
        /** The agent queued nothing; {@link HopResult#reason()} says why. */
        REFUSED,
        /** Queued, but the world had not landed when time ran out. */
        TIMED_OUT
    }

    /**
     * The end of one {@link #hop}.
     *
     * @param outcome how it ended
     * @param reason  the agent's reason string for {@link HopOutcome#REFUSED}; empty otherwise
     */
    public record HopResult(HopOutcome outcome, String reason) {

        /** True on {@link HopOutcome#ARRIVED}. */
        public boolean isArrived() {
            return outcome == HopOutcome.ARRIVED;
        }

        /** The decoded refusal; empty unless {@link HopOutcome#REFUSED}. */
        public Optional<HopRefusal> refusal() {
            return outcome == HopOutcome.REFUSED
                    ? Optional.of(HopRefusal.fromWire(reason)) : Optional.empty();
        }
    }
}
