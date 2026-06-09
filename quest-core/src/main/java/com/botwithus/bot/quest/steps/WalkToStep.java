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
import java.util.function.Predicate;

/**
 * Blocking walk to a fixed world tile via
 * {@link com.botwithus.bot.api.Navigation#walkWorldPath(int, int, int, long)}.
 * Succeeds on arrival (player within {@link #arrivalRadiusTiles} of the
 * target on the correct plane) — independent of any tracker varbit advance,
 * which is what fixes the deadlock the framework was designed to avoid.
 */
public final class WalkToStep implements QuestStep {

    private static final int DEFAULT_ARRIVAL_RADIUS = 2;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final int targetX;
    private final int targetY;
    private final int targetPlane;
    private int arrivalRadiusTiles = DEFAULT_ARRIVAL_RADIUS;
    private Duration timeout = DEFAULT_TIMEOUT;

    public WalkToStep(int x, int y, int plane) {
        this.targetX = x;
        this.targetY = y;
        this.targetPlane = plane;
    }

    public WalkToStep withTimeout(Duration t) {
        this.timeout = t;
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
    public StepResult execute(QuestContext ctx) {
        if (arrived(ctx.api())) {
            return StepResult.done();
        }
        long budget = Math.min(timeout.toMillis(), Math.max(1_000L, ctx.remainingMs()));
        WalkResult result = ctx.navigation()
                .walkWorldPath(targetX, targetY, targetPlane, budget);
        if (result == WalkResult.ARRIVED || arrived(ctx.api())) {
            return StepResult.done();
        }
        return StepResult.retry("walk " + result);
    }

    @Override
    public Predicate<QuestContext> success() {
        return ctx -> arrived(ctx.api());
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    private boolean arrived(GameAPI api) {
        GameSnapshot snap = api.snapshot();
        LocalPlayer lp = snap == null ? null : snap.self();
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
