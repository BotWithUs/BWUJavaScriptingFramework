package com.botwithus.bot.quest.steps;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.quest.QuestContext;
import com.botwithus.bot.quest.QuestState;
import com.botwithus.bot.quest.QuestStep;
import com.botwithus.bot.quest.StepResult;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * Click a scene object by name with a configurable right-click option.
 * Default action is the leftmost / index-1 option; override with
 * {@link #withAction(String)} (e.g. {@code "Climb-up"}, {@code "Operate"}).
 *
 * <p>Success isn't a single thing — for a door/ladder you'd want a plane
 * change; for an interactable that fires off a dialog you'd want the
 * dialog open. The default predicate returns true once a tile / plane
 * change is observed after execute fires, falling back to "true" if no
 * baseline was captured (e.g. the script just started). Override with
 * {@link #withSuccess(Predicate)} for stricter checks.</p>
 */
public final class ObjectInteractStep implements QuestStep {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private final String objectName;
    private String action;
    private Duration timeout = DEFAULT_TIMEOUT;
    private Predicate<QuestContext> successOverride;

    private int baselineTileX;
    private int baselineTileY;
    private int baselinePlane;
    private boolean hasBaseline;

    public ObjectInteractStep(String objectName) {
        this.objectName = objectName;
    }

    public ObjectInteractStep withAction(String optionText) {
        this.action = optionText;
        return this;
    }

    public ObjectInteractStep withTimeout(Duration t) {
        this.timeout = t;
        return this;
    }

    public ObjectInteractStep withSuccess(Predicate<QuestContext> predicate) {
        this.successOverride = predicate;
        return this;
    }

    @Override
    public String name() {
        return "objectInteract(" + objectName + (action == null ? "" : ", " + action) + ")";
    }

    @Override
    public boolean appliesTo(QuestState state, GameAPI api) {
        return true;
    }

    @Override
    public void onEnter(QuestContext ctx) {
        GameSnapshot snap = ctx.api().snapshot();
        LocalPlayer lp = snap == null ? null : snap.self();
        if (lp == null) {
            hasBaseline = false;
            return;
        }
        baselineTileX = lp.tileX();
        baselineTileY = lp.tileY();
        baselinePlane = lp.plane();
        hasBaseline = true;
    }

    @Override
    public StepResult execute(QuestContext ctx) {
        GameAPI api = ctx.api();
        SceneObject target = api.objects().nearest(objectName);
        if (target == null) {
            return StepResult.retry("object '" + objectName + "' not in scene");
        }
        boolean queued = action == null
                ? interactDefault(target)
                : target.interact(action);
        if (!queued) {
            return StepResult.retry("object '" + objectName + "' has no "
                    + (action == null ? "default" : "'" + action + "'") + " option");
        }
        return StepResult.done();
    }

    @Override
    public Predicate<QuestContext> success() {
        return successOverride != null ? successOverride : this::movedOrPlaneChanged;
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    private boolean interactDefault(SceneObject obj) {
        try {
            obj.interact(1);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean movedOrPlaneChanged(QuestContext ctx) {
        if (!hasBaseline) {
            return true;
        }
        GameSnapshot snap = ctx.api().snapshot();
        LocalPlayer lp = snap == null ? null : snap.self();
        if (lp == null) {
            return false;
        }
        if (lp.plane() != baselinePlane) {
            return true;
        }
        return lp.tileX() != baselineTileX || lp.tileY() != baselineTileY;
    }
}
