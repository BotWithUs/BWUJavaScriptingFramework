package com.botwithus.bot.api.world;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.ComponentOp;
import com.botwithus.bot.api.model.ComponentOps;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.util.Interfaces;
import com.botwithus.bot.api.util.Timing;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * Teleporting on the Lodestone Network.
 *
 * <p>Everything goes through the action queue as clicks: the minimap's home-teleport button
 * opens the network (op "Lodestone Network"), or repeats the last lodestone teleport through
 * its second op, which the game names after that lodestone ("Teleport Burthorpe", or
 * "Teleport Lumbridge" before any teleport; read live on 951-4). A destination button on
 * the network map teleports. No camera or desktop input is involved.</p>
 *
 * <p>Arrival is judged from the snapshot: the local player has to end up at least
 * {@value #ARRIVAL_DISTANCE} tiles from where it started, which a lodestone teleport always
 * does and a walk interrupted by the cast never does.</p>
 */
public final class LodestoneNetwork {

    /** How far, in tiles, the player must have moved for a teleport to count as arrived. */
    public static final int ARRIVAL_DISTANCE = 20;
    /** Wait for the network window to open after each press, in ms. */
    public static final long OPEN_TIMEOUT_MS = 6_000L;
    /** Presses of "Lodestone Network" before giving up: one more if the first does not open it. */
    private static final int OPEN_ATTEMPTS = 2;
    /** Wait after arrival for the landing animation to finish, in ms. */
    private static final long SETTLE_TIMEOUT_MS = 6_000L;
    private static final int NO_ANIMATION = -1;
    /** Default wait for arrival: the cast animation plus the load, in ms. */
    public static final long ARRIVAL_TIMEOUT_MS = 20_000L;

    static final String OPEN_OPTION = "Lodestone Network";
    /** The repeat option's name starts with this; the rest is the last lodestone's name. */
    static final String PREVIOUS_OPTION_PREFIX = "Teleport ";

    private static final GamevalRef HOME_BUTTON = new GamevalRef(GamevalType.COMPONENT,
            "TOPLEVEL_V2_MINIMAP__HOME_TELEPORT", Interfaces.componentHash(Interfaces.MINIMAP, 34));
    private static final GamevalRef NETWORK_WINDOW = new GamevalRef(GamevalType.INTERFACE,
            "THAS_LODESTONE_MAP", Lodestone.NETWORK_INTERFACE);
    private static final int OP_TELEPORT = 1;
    private static final int NO_SUB_INDEX = -1;

    /** How a teleport attempt ended. */
    public enum Result {
        /** The player arrived. */
        ARRIVED,
        /** The lodestone is not unlocked; nothing was clicked. */
        LOCKED,
        /** The network window did not open, or its button could not be found. */
        NETWORK_UNAVAILABLE,
        /** The click was queued but the player had not moved far enough when time ran out. */
        TIMED_OUT,
        /** The player is not in game. */
        NOT_IN_GAME
    }

    private final GameAPI api;

    public LodestoneNetwork(GameAPI api) {
        this.api = api;
    }

    /** True when {@code lodestone} is unlocked. */
    public boolean isUnlocked(Lodestone lodestone) {
        return lodestone.isUnlockedBy(api.getVarbit(lodestone.unlockVarbit().resolve(api.gamevals())));
    }

    /** Every unlocked lodestone. */
    public List<Lodestone> unlocked() {
        return List.of(Lodestone.values()).stream().filter(this::isUnlocked).toList();
    }

    /** True when the network window is open. */
    public boolean isOpen() {
        return api.isInterfaceOpen(NETWORK_WINDOW.resolve(api.gamevals()));
    }

    /** Clicks "Lodestone Network" on the home-teleport button. Returns whether it was queued. */
    public boolean open() {
        return pressHomeOption(ops -> ops.opNamed(OPEN_OPTION));
    }

    /** {@link #teleport(Lodestone, long)} with {@link #ARRIVAL_TIMEOUT_MS}. */
    public Result teleport(Lodestone lodestone) {
        return teleport(lodestone, ARRIVAL_TIMEOUT_MS);
    }

    /**
     * Teleports to {@code lodestone}: opens the network if needed, clicks the destination,
     * and waits up to {@code timeoutMs} for arrival. Blocks the calling thread.
     */
    public Result teleport(Lodestone lodestone, long timeoutMs) {
        LocalPlayer start = self();
        if (start == null) {
            return Result.NOT_IN_GAME;
        }
        if (!isUnlocked(lodestone)) {
            return Result.LOCKED;
        }
        if (!ensureOpen()) {
            return Result.NETWORK_UNAVAILABLE;
        }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, OP_TELEPORT, NO_SUB_INDEX,
                lodestone.button().resolve(api.gamevals())));
        return awaitArrival(start, timeoutMs);
    }

    /**
     * Repeats the last lodestone teleport (the home button's "Teleport &lt;lodestone&gt;"
     * option) and waits up to {@code timeoutMs} for arrival. Before any lodestone teleport
     * this is the home teleport to Lumbridge.
     */
    public Result teleportToPrevious(long timeoutMs) {
        LocalPlayer start = self();
        if (start == null) {
            return Result.NOT_IN_GAME;
        }
        if (!pressHomeOption(ops -> ops.ops().stream()
                .filter(op -> op.name().startsWith(PREVIOUS_OPTION_PREFIX))
                .mapToInt(ComponentOp::op).findFirst())) {
            return Result.NETWORK_UNAVAILABLE;
        }
        return awaitArrival(start, timeoutMs);
    }

    /** {@link #teleportToPrevious(long)} with {@link #ARRIVAL_TIMEOUT_MS}. */
    public Result teleportToPrevious() {
        return teleportToPrevious(ARRIVAL_TIMEOUT_MS);
    }

    static boolean hasArrived(LocalPlayer start, LocalPlayer now) {
        int moved = Math.max(Math.abs(now.tileX() - start.tileX()),
                Math.abs(now.tileY() - start.tileY()));
        return moved >= ARRIVAL_DISTANCE || now.plane() != start.plane() && moved > 0;
    }

    private boolean ensureOpen() {
        for (int attempt = 0; attempt < OPEN_ATTEMPTS && !isOpen(); attempt++) {
            if (!open()) {
                return false;
            }
            Timing.delayUntil(this::isOpen, OPEN_TIMEOUT_MS);
        }
        return isOpen();
    }

    /**
     * Waits for the jump, then for the landing animation to end: a click sent while it
     * plays is dropped by the game (seen live: the next network open did nothing).
     */
    private Result awaitArrival(LocalPlayer start, long timeoutMs) {
        boolean arrived = Timing.delayUntil(() -> {
            LocalPlayer now = self();
            return now != null && hasArrived(start, now);
        }, timeoutMs);
        if (!arrived) {
            return Result.TIMED_OUT;
        }
        Timing.delayUntil(() -> {
            LocalPlayer now = self();
            return now != null && now.animationId() == NO_ANIMATION;
        }, SETTLE_TIMEOUT_MS);
        return Result.ARRIVED;
    }

    private boolean pressHomeOption(Function<ComponentOps, OptionalInt> pick) {
        GamevalIndex index = api.gamevals();
        int hash = HOME_BUTTON.resolve(index);
        ComponentOps ops = api.getComponentOps(Interfaces.interfaceIdFromHash(hash),
                Interfaces.componentIdFromHash(hash), NO_SUB_INDEX);
        OptionalInt op = ops == null ? OptionalInt.empty() : pick.apply(ops);
        if (op.isEmpty()) {
            return false;
        }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, op.getAsInt(), NO_SUB_INDEX, hash));
        return true;
    }

    private LocalPlayer self() {
        GameSnapshot snapshot = api.snapshot();
        if (snapshot == null || snapshot.gameState() != GameSnapshot.GAME_STATE_IN_GAME) {
            return null;
        }
        return snapshot.self();
    }
}
