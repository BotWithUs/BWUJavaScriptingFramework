package com.botwithus.bot.quest.steps;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.WalkResult;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.quest.QuestContext;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.StepResult;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.function.Predicate;

/**
 * Blocking walk to a fixed world tile via
 * {@link com.botwithus.bot.api.Navigation#walkWorldPath(int, int, int, long)}.
 * Succeeds on arrival (player within {@link #arrivalRadiusTiles} of the
 * target on the correct plane) — independent of any tracker varbit advance,
 * which is what fixes the deadlock the framework was designed to avoid.
 *
 * <p>Having no local player is treated as not ready rather than as a failed
 * walk. Login, loading and world hops pass through that state, and a walk with
 * no player fails at once, so counting it as a retry would stop the script
 * within seconds. The step waits instead, and aborts only once the player has
 * been absent for {@link #DEFAULT_NO_PLAYER_LIMIT} without a break.</p>
 */
public final class WalkToStep implements QuestStep {

    /** How long a player may stay absent before the step gives up, unless overridden. */
    public static final Duration DEFAULT_NO_PLAYER_LIMIT = Duration.ofSeconds(60);

    private static final int DEFAULT_ARRIVAL_RADIUS = 2;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final int NO_PLAYER_POLL_MS = 1_000;

    private final int targetX;
    private final int targetY;
    private final int targetPlane;
    private int arrivalRadiusTiles = DEFAULT_ARRIVAL_RADIUS;
    private Duration timeout = DEFAULT_TIMEOUT;
    private Duration noPlayerLimit = DEFAULT_NO_PLAYER_LIMIT;
    /** {@link System#nanoTime()} when the current absence began; empty while a player is present. */
    private OptionalLong noPlayerSinceNanos = OptionalLong.empty();

    public WalkToStep(int x, int y, int plane) {
        this.targetX = x;
        this.targetY = y;
        this.targetPlane = plane;
    }

    public WalkToStep withTimeout(Duration t) {
        this.timeout = t;
        return this;
    }

    /** Overrides how long the player may stay absent before the step aborts. */
    public WalkToStep withNoPlayerLimit(Duration limit) {
        this.noPlayerLimit = limit;
        return this;
    }

    public WalkToStep withArrivalRadius(int tiles) {
        this.arrivalRadiusTiles = Math.max(0, tiles);
        return this;
    }

    @Override
    public String name() {
        return "walkTo(" + targetX + "," + targetY + "," + targetPlane + ")";
    }

    @Override
    public boolean appliesTo(QuestState state, GameAPI api) {
        return true;
    }

    @Override
    public void onEnter(QuestContext ctx) {
        noPlayerSinceNanos = OptionalLong.empty();
    }

    @Override
    public StepResult execute(QuestContext ctx) {
        if (arrived(ctx.api())) {
            return StepResult.done();
        }
        if (localPlayer(ctx.api()) == null) {
            return waitForPlayer();
        }
        noPlayerSinceNanos = OptionalLong.empty();
        long budget = Math.min(timeout.toMillis(), Math.max(1_000L, ctx.remainingMs()));
        WalkResult result = ctx.navigation()
                .walkWorldPath(targetX, targetY, targetPlane, budget);
        if (result == WalkResult.ARRIVED || arrived(ctx.api())) {
            return StepResult.done();
        }
        if (localPlayer(ctx.api()) == null) {
            return waitForPlayer();
        }
        return StepResult.retry("walk " + result);
    }

    private StepResult waitForPlayer() {
        long now = System.nanoTime();
        long since = noPlayerSinceNanos.orElse(now);
        noPlayerSinceNanos = OptionalLong.of(since);
        if (now - since >= noPlayerLimit.toNanos()) {
            return StepResult.abort("no local player for " + noPlayerLimit.toSeconds() + "s");
        }
        return StepResult.continueIn(NO_PLAYER_POLL_MS);
    }

    @Override
    public Predicate<QuestContext> success() {
        return ctx -> arrived(ctx.api());
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    private static LocalPlayer localPlayer(GameAPI api) {
        GameSnapshot snap = api.snapshot();
        return snap == null ? null : snap.self();
    }

    private boolean arrived(GameAPI api) {
        LocalPlayer lp = localPlayer(api);
        if (lp == null) {
            return false;
        }
        if (lp.plane() != targetPlane) {
            return false;
        }
        int dx = lp.tileX() - targetX;
        int dy = lp.tileY() - targetY;
        return (dx * dx) + (dy * dy) <= arrivalRadiusTiles * arrivalRadiusTiles;
    }
}
