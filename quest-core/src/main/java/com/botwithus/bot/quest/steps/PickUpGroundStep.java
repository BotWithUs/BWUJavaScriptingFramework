package com.botwithus.bot.quest.steps;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.GroundItem;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.quest.QuestContext;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.StepResult;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

/**
 * Pick up the nearest ground item matching the configured id or name.
 * Execute issues the "Take" interact; success polls the backpack for the
 * item appearing (count strictly greater than the baseline at
 * {@link #onEnter}).
 */
public final class PickUpGroundStep implements QuestStep {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private final int itemId;
    private final String itemName;
    private Duration timeout = DEFAULT_TIMEOUT;
    private int baselineCount;

    public PickUpGroundStep(int itemId, String itemName) {
        this.itemId = itemId;
        this.itemName = itemName;
    }

    public PickUpGroundStep withTimeout(Duration t) {
        this.timeout = t;
        return this;
    }

    @Override
    public String name() {
        return "pickUpGround(" + identifier() + ")";
    }

    @Override
    public boolean appliesTo(QuestState state, GameAPI api) {
        return true;
    }

    @Override
    public void onEnter(QuestContext ctx) {
        baselineCount = backpackCount(ctx.api().backpack());
    }

    @Override
    public StepResult execute(QuestContext ctx) {
        GameAPI api = ctx.api();
        GroundItem target = findGround(api);
        if (target == null) {
            return StepResult.retry("ground item '" + identifier() + "' not in scene");
        }
        if (!target.interact("Take")) {
            return StepResult.retry("ground item '" + identifier() + "' has no Take option");
        }
        return StepResult.done();
    }

    @Override
    public Predicate<QuestContext> success() {
        return ctx -> backpackCount(ctx.api().backpack()) > baselineCount;
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    private GroundItem findGround(GameAPI api) {
        if (itemId >= 0) {
            return api.groundItems().query().withId(itemId).nearest();
        }
        if (itemName == null) {
            return null;
        }
        List<GroundItem> matches = api.groundItems().query().named(itemName).all();
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private int backpackCount(Backpack bag) {
        if (itemId >= 0) {
            return bag.count(itemId);
        }
        return itemName == null ? 0 : bag.count(itemName);
    }

    private String identifier() {
        return itemId >= 0 ? Integer.toString(itemId) : itemName;
    }
}
