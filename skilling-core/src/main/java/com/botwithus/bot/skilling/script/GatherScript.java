package com.botwithus.bot.skilling.script;

import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.model.WalkResult;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.skilling.atlas.Spot;
import com.botwithus.bot.skilling.inventory.ResourceBox;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reusable gather loop shared by gathering skills (Woodcutting, Mining, Fishing).
 * Data-driven: the resource's loc placements come from the Atlas, so the same loop
 * works for any resource by changing the item id + interact option.
 *
 * <p>Each tick: bank when full → wait if already gathering/moving → find the
 * nearest resource node (matched by the Atlas loc ids that yield the resource) →
 * if none is loaded, walk to the nearest Atlas gather spot → interact the gather
 * option.</p>
 *
 * <p>Targeting resolves through {@link #targetSpots()}, which defaults to the
 * Atlas placements keyed by {@link #resourceItemId()}. Subclasses override it for
 * resources whose product item the skilling table doesn't resolve (e.g. normal
 * trees, which are {@code loc_name='Tree'} with a null item).</p>
 */
public abstract class GatherScript extends SkillScript {

    protected GatherScript() {}

    // ------------------------------------------------------------- contract

    /** The item this script gathers (e.g. 1511 = Logs). Used for capability + drop. */
    protected abstract int resourceItemId();

    /** The scene-object option that gathers it (e.g. {@code "Chop down"}). */
    protected abstract String gatherOption();

    /** {@code StatType} id of the skill, for {@link #level}/{@link #xp} checks. */
    protected abstract int skillId();

    // ------------------------------------------------------------- tuning

    protected int scanRadius() { return 18; }
    protected int idleWaitMs() { return 600; }
    protected int afterActionMs() { return 1200; }

    /**
     * How to dispose of a full backpack — bank, drop (power-gather), or fill a
     * {@link #resourceBox() resource box} and bank only when it too is full.
     * Defaults to {@link Disposal#BANK}.
     */
    protected Disposal disposalMode() { return Disposal.BANK; }

    /**
     * The resource box (wood box / ore box) to fill in {@link Disposal#BOX} mode,
     * or {@code null} when the script has none. Ignored in BANK / DROP modes.
     */
    protected ResourceBox resourceBox() { return null; }

    /**
     * Raw 1-based "Drop" option index, used as a fallback when the item's cache
     * options aren't available (e.g. {@code 7} for logs). {@code -1} = name only.
     */
    protected int dropOptionFallbackIndex() { return -1; }

    /** Override to stop on a level/quantity target (return {@code true} to stop). */
    protected boolean reachedTarget() { return false; }

    /**
     * The Atlas placements this script targets. Default: keyed by
     * {@link #resourceItemId()}. Override to target by loc name (or any other
     * Atlas query) when the resource's product item isn't item-keyed in the data.
     */
    protected List<Spot> targetSpots() {
        return atlas == null ? List.of() : atlas.gatherSpots(resourceItemId());
    }

    // ------------------------------------------------------------- state

    private List<Spot> spots = List.of();        // resolved target placements
    private Set<Integer> resourceLocIds = Set.of();

    // Disposal state machine (BOX mode). `boxFillIssued` latches a Fill attempt so
    // a still-full backpack on the next tick means the box is full; `bankPhase`
    // sequences the empty/deposit/withdraw-box-back steps once the bank is open. Both
    // reset whenever the backpack has room again (see onTick).
    private boolean boxFillIssued = false;
    private int bankPhase = 0;
    private boolean warnedNoBox = false;

    /** Set asynchronously by {@link #requestStop()} (e.g. a UI Stop button). */
    private volatile boolean stopRequested = false;

    /** Request a graceful stop; the loop ends on its next tick. Thread-safe. */
    public void requestStop() {
        stopRequested = true;
    }

    /** Whether a stop has been requested (for status displays). */
    public boolean isStopRequested() {
        return stopRequested;
    }

    @Override
    protected void onSetup() {
        refreshTargets();
    }

    /**
     * (Re)resolve the target placements from {@link #targetSpots()} and the loc
     * type-ids to match in the scene. Called at setup and again whenever config
     * changes the target (so {@code onConfigUpdate} edits take effect live).
     */
    protected final void refreshTargets() {
        spots = targetSpots();
        Set<Integer> ids = new HashSet<>();
        for (Spot s : spots) {
            ids.add(s.loc());
        }
        resourceLocIds = ids;
        log.info("Targeting {} Atlas placements across {} loc type(s) for resource {}",
                spots.size(), ids.size(), resourceItemId());
        if (spots.isEmpty()) {
            log.warn("No Atlas placements resolved — will only chop nodes already in the loaded scene "
                    + "(override targetSpots() or check the resource id/Atlas).");
        }
    }

    @Override
    protected int onTick() {
        if (stopRequested) {
            log.info("Stop requested — stopping.");
            return -1;
        }
        if (!inGame()) {
            return idleWaitMs();
        }
        if (reachedTarget()) {
            log.info("Target reached — stopping.");
            return -1;
        }
        if (backpack.isFull()) {
            return handleFull();
        }
        // A bank close-out (deposit → empty box → deposit) can still be mid-sequence
        // even though the pack now has room — finish it before chopping again. It
        // resets bankPhase to 0 itself when done.
        if (bankPhase != 0) {
            return bankAndEmptyBox(resourceBox());
        }
        // Backpack has room and no bank visit pending → the next box fill is fresh.
        boxFillIssued = false;
        if (!isIdle()) {
            return idleWaitMs();        // already gathering / walking
        }

        SceneObject node = findNode();
        if (node == null) {
            return walkToResource();    // nothing loaded nearby → walk to an Atlas spot
        }
        if (node.interact(gatherOption())) {
            log.debug("Gathering {} at ({},{})", node.name(), node.tileX(), node.tileY());
            return afterActionMs();
        }
        log.warn("Node {} exposes no '{}' option", node, gatherOption());
        return idleWaitMs();
    }

    // ------------------------------------------------------------- internals

    /**
     * Nearest resource node in the loaded scene: a loc whose id is one of the
     * target placements AND that exposes the gather option, falling back to any
     * object exposing the option (covers resources we have no Atlas loc ids for).
     */
    private SceneObject findNode() {
        if (!resourceLocIds.isEmpty()) {
            SceneObject byLoc = api.objects().query()
                    .withinDistance(scanRadius())
                    .filter(o -> resourceLocIds.contains(o.typeId()) && o.hasOption(gatherOption()))
                    .nearest();
            if (byLoc != null) {
                return byLoc;
            }
        }
        return api.objects().query()
                .withinDistance(scanRadius())
                .filter(o -> o.hasOption(gatherOption()))
                .nearest();
    }

    private int walkToResource() {
        LocalPlayer p = player();
        if (p == null) {
            return idleWaitMs();
        }
        Spot best = nearestSpot(p.tileX(), p.tileY(), p.plane());
        if (best == null) {
            if (atlas == null) {
                log.warn("No '{}' resource in range and no Atlas to walk by", gatherOption());
                return idleWaitMs() * 3;
            }
            log.warn("No Atlas placement reachable for resource {} on plane {}", resourceItemId(), p.plane());
            return 5000;
        }
        log.info("Walking to {} resource '{}' at ({},{},{})",
                gatherOption(), best.locName(), best.x(), best.y(), best.plane());
        WalkResult r = nav.walkWorldPath(best.x(), best.y(), best.plane());
        if (r != WalkResult.ARRIVED) {
            log.warn("Walk to resource ended {}", r);
        }
        return idleWaitMs();
    }

    /** Nearest target placement on the player's plane (Chebyshev), or {@code null}. */
    private Spot nearestSpot(int x, int y, int plane) {
        Spot best = null;
        int bestD = Integer.MAX_VALUE;
        for (Spot s : spots) {
            if (s.plane() != plane) {
                continue;
            }
            int d = Math.max(Math.abs(s.x() - x), Math.abs(s.y() - y));
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    private int handleFull() {
        return switch (disposalMode()) {
            case DROP -> dropLoose();
            case BANK -> bankStep();
            case BOX -> boxStep();
        };
    }

    /** Power-gather: drop the gathered item (one slot per tick). */
    protected int dropLoose() {
        if (!backpack.interactFirst(resourceItemId(), "Drop") && dropOptionFallbackIndex() > 0) {
            backpack.interactFirst(resourceItemId(), dropOptionFallbackIndex());
        }
        return afterActionMs();
    }

    /** Plain banking: walk → open → deposit, one step per tick. */
    protected int bankStep() {
        boolean deposited = banking.bankResource(resourceItemId());
        return deposited ? idleWaitMs() : 3000;
    }

    /**
     * Trip-extender. Try to absorb the loose resources into the box; if the box
     * still leaves the backpack full (it's full too), bank — emptying the box into
     * the bank first. The {@code boxFillIssued} latch distinguishes the first Fill
     * attempt from a re-entry after a no-op Fill (it resets in onTick the moment
     * the backpack has room again, so a Fill that frees space resumes gathering).
     */
    private int boxStep() {
        ResourceBox box = resourceBox();
        if (box == null) {
            return bankAndEmptyBox(null);
        }
        if (!box.isPresent()) {
            // The box isn't in the pack — it may be sitting in the bank (deposited
            // last trip). Bank the loose resources; bankAndEmptyBox reclaims the box
            // from the bank as its final step, so a box trip recovers itself.
            if (!warnedNoBox) {
                log.warn("Disposal=BOX but no resource box in the backpack — banking the "
                        + "resources and reclaiming the box from the bank if it's there.");
                warnedNoBox = true;
            }
            return bankAndEmptyBox(box);
        }
        if (!boxFillIssued) {
            boxFillIssued = true;
            if (box.fill()) {
                log.debug("Filling resource box to extend the trip");
                return afterActionMs();
            }
            // Couldn't issue Fill at all (no option resolved) → fall back to banking.
            return bankAndEmptyBox(box);
        }
        // We filled last cycle and the backpack is still full → the box is full.
        log.debug("Resource box full — banking + emptying");
        return bankAndEmptyBox(box);
    }

    /**
     * Bank a full trip while preserving the resource box. Sequenced one step per
     * tick once the bank is open: empty the box into the bank (while it's still in
     * the pack) → deposit the loose resources (which also banks the now-empty box) →
     * withdraw the box back, so the trip-extender survives into the next trip. The
     * withdraw step also reclaims a box that an earlier trip left in the bank.
     */
    private int bankAndEmptyBox(ResourceBox box) {
        if (!banking.isOpen()) {
            if (!banking.walkToNearestBank()) {
                return 3000;
            }
            if (!banking.openNearbyBooth()) {
                log.warn("At a bank tile but found no booth to open");
            }
            return idleWaitMs(); // observe the opened interface on the next tick
        }
        return switch (bankPhase) {
            case 0 -> {
                // Empty the box BEFORE depositing: its contents land in the bank
                // while the now-empty box is still in the pack. Depositing first
                // would bank the box with its resources still trapped inside.
                if (box != null && box.isPresent()) {
                    box.emptyAtBank();
                }
                bankPhase = 1;
                yield afterActionMs();
            }
            case 1 -> {
                banking.depositInventory();
                bankPhase = (box != null) ? 2 : 99;
                yield afterActionMs();
            }
            case 2 -> {
                // depositInventory() banked the (now-empty) box too — pull it back so
                // the next trip starts with the box in the pack. Also recovers a box
                // an earlier trip stranded in the bank.
                box.bankedTier().ifPresent(banking::withdrawOne);
                bankPhase = 99;
                yield afterActionMs();
            }
            default -> {
                bankPhase = 0;
                boxFillIssued = false;
                yield idleWaitMs();
            }
        };
    }
}
