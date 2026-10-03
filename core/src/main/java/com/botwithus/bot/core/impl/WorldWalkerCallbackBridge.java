package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.dialog.Dialog;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.inventory.Equipment;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.api.model.VarbitRead;
import com.botwithus.bot.api.model.VarpRead;
import com.botwithus.bot.api.snapshot.DynamicRegion;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.Inventory;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Location;
import com.botwithus.bot.api.snapshot.Npc;
import com.botwithus.bot.api.snapshot.Skill;
import com.botwithus.bot.api.util.Interfaces;
import com.botwithus.bot.core.worldwalker.ChainStepKind;
import com.botwithus.bot.core.worldwalker.CapabilitySnapshot;
import com.botwithus.bot.core.worldwalker.DialogueAnswerText;
import com.botwithus.bot.core.worldwalker.WorldWalkerException;
import com.botwithus.bot.core.worldwalker.WwCallbacks;
import com.botwithus.bot.core.worldwalker.WwEvent;
import com.botwithus.bot.core.worldwalker.WwGoal;
import com.botwithus.bot.core.worldwalker.WwTile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

final class WorldWalkerCallbackBridge implements WwCallbacks {

    private static final Logger log = LoggerFactory.getLogger(WorldWalkerCallbackBridge.class);

    private static final long TICK_MS = 600L;

    /** Footprint distance a transition's loc normally sits within: on or beside its row tile. */
    private static final int LOC_FOOTPRINT_REACH = 1;

    /**
     * Largest footprint distance still accepted for a transition's loc. Some dataset rows sit a
     * few tiles off the loc they name; past this the loc is treated as absent.
     */
    private static final int LOC_FALLBACK_REACH = 4;

    // Opportunistic movement abilities, piggy-backed onto walkTo. We only see
    // the click target, never the path, so every rule is judged on the
    // player->target vector:
    //  - Surge launches the avatar 10 tiles forward in its current facing. It
    //    fires on a >=8-tile hop that is near-straight: within 1/5 of the major
    //    axis of one of the 8 directions (see isNearStraight). The executor's
    //    randomized long clicks are rarely exactly 8-way, so an exact test
    //    would all but stop Surge firing.
    //  - Dive (or Bladed Dive, which replaces it on the bar) jumps onto a
    //    chosen tile up to 10 away. The walk target is a planner path tile, so
    //    it is standable; Dive fires on a 6..10-tile hop and is preferred over
    //    Surge when both qualify.
    // Neither fires within ABILITY_GOAL_GUARD of the final goal, and each
    // eligible walk only rolls a chance to fire so the pattern isn't
    // mechanical. The bar slot is found by scanning the action bars for the
    // ability's icon, read from its cache struct, and cooldown comes from the
    // ability's cooldown-end varc against the client game cycle.
    private static final int  MAGIC_SKILL_TYPE   = 6;     // StatType.id for Magic
    private static final int  SURGE_MIN_MAGIC    = 24;    // ability unlock level
    private static final int  SURGE_MIN_TILES    = 8;     // don't burn cooldown on short hops
    private static final int  DIVE_MIN_TILES     = 6;     // shorter hops aren't worth a cooldown
    private static final int  DIVE_MAX_TILES     = 10;    // Dive's reach
    private static final int  ABILITY_GOAL_GUARD = 12;    // skip if close enough to overshoot
    // Near-straight tolerance as a divisor of the major axis: the off-line
    // error may be at most major / 5 (a 0.2 tolerance, kept integral so a
    // boundary case can't flip on floating-point rounding).
    private static final int  SURGE_STRAIGHT_TOLERANCE_DIVISOR = 5;
    // Chance an eligible walk actually fires the ability.
    private static final double SURGE_FIRE_CHANCE = 0.70;
    private static final double DIVE_FIRE_CHANCE  = 0.60;
    // Every action bar an ability can sit on, scanned in this order.
    static final List<Integer> ACTION_BAR_IFACES = List.of(1430, 1670, 1671, 1672, 1673);
    // How long a scan result (hit or miss) stands before the bars are scanned
    // again: bars and presets change mid-run, so neither outcome is final.
    private static final long SLOT_RESCAN_MS = 60_000L;
    // Cache struct ids and the param holding each ability's bar icon sprite.
    static final String STRUCT_PARAM_ICON_SPRITE = "2802";
    static final int    STRUCT_SURGE       = 14726;   // COMBATV2_ABILITY_MAGIC_SURGE
    static final int    STRUCT_DIVE        = 47129;   // COMBATV2_ABILITY_ATTACK_DIVE
    static final int    STRUCT_BLADED_DIVE = 1488;    // COMBATV2_ABILITY_ATTACK_BLADED_DIVE
    // Cooldown-end varcs, in client game cycles.
    static final int    VARC_SURGE_COOLDOWN_END       = 2194;
    static final int    VARC_BLADED_DIVE_COOLDOWN_END = 6038;
    // Wall-clock floor between two fires of one ability, so a bad varc read
    // can't spam clicks. Plain Dive has no cooldown varc of its own that we
    // know of, so it borrows Bladed Dive's and keeps the full cache cooldown
    // (34 ticks, the same for all three) as its floor.
    private static final long ABILITY_REFIRE_FLOOR_MS = 6_000L;
    private static final int  ABILITY_CACHE_COOLDOWN_TICKS = 34;
    private static final long DIVE_REFIRE_FLOOR_MS = ABILITY_CACHE_COOLDOWN_TICKS * TICK_MS;
    // Bar-slot click shape: right-click option 1, no sub-slot.
    private static final int  ABILITY_CLICK_OPTION = 1;
    private static final int  NO_SUB_SLOT = -1;
    private static final int  SELECT_TILE_PARAM = 0;
    // Ticks to wait after an ability before re-queueing the walk it cancelled.
    private static final int  RESUME_WALK_MIN_TICKS = 1;
    private static final int  RESUME_WALK_MAX_TICKS = 2;
    // LocalPlayer.animationId() when the player is idle.
    private static final int  NO_ANIMATION = -1;
    // Sentinel for "no icon sprite": sprite ids are never negative.
    private static final int  NO_SPRITE = -1;
    // Sentinel for "never happened" on a slot's scan / fire timestamps.
    private static final long NEVER_MS = Long.MIN_VALUE;

    static final AbilitySpec SURGE_SPEC = new AbilitySpec(
            "Surge", STRUCT_SURGE, VARC_SURGE_COOLDOWN_END, ABILITY_REFIRE_FLOOR_MS);
    static final AbilitySpec BLADED_DIVE_SPEC = new AbilitySpec(
            "Bladed Dive", STRUCT_BLADED_DIVE, VARC_BLADED_DIVE_COOLDOWN_END,
            ABILITY_REFIRE_FLOOR_MS);
    static final AbilitySpec DIVE_SPEC = new AbilitySpec(
            "Dive", STRUCT_DIVE, VARC_BLADED_DIVE_COOLDOWN_END, DIVE_REFIRE_FLOOR_MS);
    private static final AbilityFamily SURGE = new AbilityFamily(
            "surge", List.of(SURGE_SPEC), SURGE_FIRE_CHANCE, false);
    private static final AbilityFamily DIVE = new AbilityFamily(
            "dive", List.of(BLADED_DIVE_SPEC, DIVE_SPEC), DIVE_FIRE_CHANCE, true);

    // Varps the walker's transitions gate on through `varp` / `varp_at_least`.
    // STAND-IN: the executor batches every varbit and item id its artifact's
    // requirements reference, but it has no way to name varps to the host --
    // it learns them only from readCapability, and an absent varp reads 0, so
    // every varp gate is denied. Until the walker exports the artifact's
    // requirement varp ids (see the WorldWalker datasets README, "A varp gate
    // is denied by the live bot today"), the host supplies the ones the
    // shipped dataset uses. Replace this list with the artifact's own once
    // that export exists; a varp gate added to the dataset without a matching
    // id here stays denied, which is the safe direction.
    private static final int VARP_TREE_GNOME_VILLAGE = 2661;  // spirit trees, complete at 9
    private static final int VARP_THE_GRAND_TREE     = 2740;  // Gnome Stronghold tree, 160
    private static final int VARP_CABIN_FEVER        = 2326;  // Mos Le'Harmless charter, 140
    private static final int VARP_REGICIDE           = 2102;  // Port Tyras charter, 15
    private static final int VARP_ONE_SMALL_FAVOUR   = 2671;  // Feldip Hills glider, 200
    static final List<Integer> REQUIREMENT_VARPS = List.of(
            VARP_TREE_GNOME_VILLAGE, VARP_THE_GRAND_TREE, VARP_CABIN_FEVER, VARP_REGICIDE,
            VARP_ONE_SMALL_FAVOUR);

    private final GameAPI api;
    private final Supplier<GameSnapshot> snapshotSource;
    private final AtomicBoolean cancel;
    private final Consumer<WwEvent> eventSink;
    private final WwGoal goal;
    private final List<Integer> requirementVarps;
    private final RandomGenerator rng;
    private final LongSupplier clockMs;
    private final Sleeper sleeper;

    // Per-run movement-ability state. Callbacks arrive on the executor thread
    // only, so none of this is shared.
    private final AbilitySlot surgeSlot = new AbilitySlot(SURGE);
    private final AbilitySlot diveSlot  = new AbilitySlot(DIVE);
    // Icon sprite per struct id; only successful reads are memoised.
    private final Map<Integer, Integer> iconSpriteByStruct = new HashMap<>();

    WorldWalkerCallbackBridge(GameAPI api,
                              Supplier<GameSnapshot> snapshotSource,
                              AtomicBoolean cancel,
                              Consumer<WwEvent> eventSink,
                              WwGoal goal) {
        this(api, snapshotSource, cancel, eventSink, goal, REQUIREMENT_VARPS);
    }

    /** As above, reading {@code requirementVarps} into every capability snapshot. */
    WorldWalkerCallbackBridge(GameAPI api,
                              Supplier<GameSnapshot> snapshotSource,
                              AtomicBoolean cancel,
                              Consumer<WwEvent> eventSink,
                              WwGoal goal,
                              List<Integer> requirementVarps) {
        this(api, snapshotSource, cancel, eventSink, goal, requirementVarps, Pacing.system());
    }

    /** As above, taking randomness, time and sleeping from {@code pacing}. */
    WorldWalkerCallbackBridge(GameAPI api,
                              Supplier<GameSnapshot> snapshotSource,
                              AtomicBoolean cancel,
                              Consumer<WwEvent> eventSink,
                              WwGoal goal,
                              List<Integer> requirementVarps,
                              Pacing pacing) {
        Objects.requireNonNull(pacing, "pacing");
        this.rng = pacing.rng();
        this.clockMs = pacing.clockMs();
        this.sleeper = pacing.sleeper();
        this.api = Objects.requireNonNull(api, "api");
        this.snapshotSource = Objects.requireNonNull(snapshotSource, "snapshotSource");
        this.cancel = Objects.requireNonNull(cancel, "cancel");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.goal = goal;
        this.requirementVarps = List.copyOf(requirementVarps);
    }

    @Override
    public WwTile readPosition() {
        LocalPlayer lp = currentPlayer();
        if (lp == null) {
            return new WwTile(0, 0, 0);
        }
        return new WwTile(lp.tileX(), lp.tileY(), lp.plane());
    }

    /**
     * The player's base skill levels. Skills are the one capability class the executor cannot pull
     * by itself: it batches every varbit and item id the artifact's requirements reference, but
     * nothing tells it which skills matter, and an absent id reads 0 — so a level gate is denied
     * outright and the transitions behind it are never planned. Base rather than boosted level, so
     * a route is not planned through a gate whose boost has lapsed by the time the player gets
     * there.
     *
     * <p>Varps are the other class the executor cannot request, so the snapshot also carries
     * {@link #REQUIREMENT_VARPS}, read in one batched call per (re-)plan. Not cached across
     * plans: a re-plan must see a quest completed mid-walk.</p>
     */
    @Override
    public CapabilitySnapshot readCapability() {
        LocalPlayer lp = currentPlayer();
        if (lp == null) {
            return CapabilitySnapshot.empty();
        }
        CapabilitySnapshot.Builder caps = CapabilitySnapshot.builder();
        for (Skill s : lp.skills()) {
            caps.skill(s.typeId(), s.actualLevel());
        }
        readRequirementVarps(caps);
        return caps.build();
    }

    // One readVarps round-trip for every requirement varp. A varp with no value
    // (no such varp, or the read could not be made) is left out, so the walker
    // reads it as 0 ("not present") rather than the host's -1 sentinel, as
    // readVarbit does. A failed or mis-sized read adds none of them, so each
    // varp gate is denied -- never a partial write that pairs an id with
    // another's value.
    private void readRequirementVarps(CapabilitySnapshot.Builder caps) {
        if (requirementVarps.isEmpty()) {
            return;
        }
        try {
            List<VarpRead> reads = api.readVarps(requirementVarps);
            if (reads == null || reads.size() != requirementVarps.size()) {
                log.warn("ww readCapability: readVarps returned {} reads for {} varps",
                        reads == null ? 0 : reads.size(), requirementVarps.size());
                return;
            }
            for (int i = 0; i < reads.size(); i++) {
                VarpRead read = reads.get(i);
                if (read.hasValue()) {
                    caps.varp(requirementVarps.get(i), read.value());
                }
            }
        } catch (RuntimeException e) {
            log.debug("ww readCapability: readVarps({} varps) failed: {}",
                    requirementVarps.size(), e.toString());
        }
    }

    /**
     * The walker's C ABI treats {@code 0} as "not present", so a varbit with no value (no
     * such varbit, or its base could not be read) is reported as {@code 0}, never as the
     * host's {@code -1} sentinel: a gate compared with {@code != 0} must stay shut. A varbit
     * that has a value is reported as-is, including a full-width one that decodes to
     * {@code -1}.
     */
    @Override
    public int readVarbit(int id) {
        try {
            return walkerValue(api.readVarbit(id));
        } catch (RuntimeException e) {
            log.debug("readVarbit({}) failed: {}", id, e.toString());
            return 0;
        }
    }

    /**
     * The scene's dynamic-region grid, so the planner can path inside a
     * player-owned house or a Dungeoneering floor instead of reading the whole
     * instance as solid.
     *
     * <p>Three deliberate choices here, each of which fails silently and wrongly
     * if reversed:</p>
     * <ul>
     *   <li>{@code copyOfStable} rather than the snapshot's flyweight. The
     *       flyweight reads a live double-buffered mapping with no seqlock, and
     *       the grid is up to 64 KB; a torn read resolves to plausible wrong
     *       tiles rather than failing.</li>
     *   <li>{@code isInstance()} as the predicate, never {@code sceneMode()} —
     *       the two are independent client fields that disagree on a
     *       never-written buffer and on a scene caught mid-rebuild.</li>
     *   <li>Both "cannot describe this instance" cases — a producer-truncated
     *       grid and a grid that tore on every copy attempt — throw. Returning
     *       {@code null} for either would marshal as "not an instance" and plan
     *       the walk against the overworld collision that happens to share this
     *       instance's coordinates, which is the same silent wrong answer in
     *       both cases and so gets the same treatment.</li>
     * </ul>
     *
     * <p>Throwing aborts the run: the marshaller records it, cancellation trips
     * at the next safe point, and {@code runExecutor} rethrows on the calling
     * thread. Note the executor may still dispatch one {@code walkTo} before
     * that poll lands, so this bounds the damage to a single stray click rather
     * than eliminating it outright.</p>
     */
    @Override
    public DynamicRegion readInstance() {
        GameSnapshot snap = snapshotSource.get();
        if (snap == null) {
            return null;
        }
        DynamicRegion region = snap.dynamicRegion();
        if (region == null || !region.isInstance()) {
            return null;
        }
        if (region.isTruncated()) {
            // The producer publishes zero chunks when it truncates, so there is
            // no partial grid to path through — the scene is simply an instance
            // we cannot describe at all.
            log.warn("ww readInstance: instance grid {}x{} chunks needs {} descriptors but the"
                            + " producer dropped it (over the wire cap); failing the walk rather"
                            + " than planning against static collision",
                    region.gridW(), region.gridH(), region.requiredChunks());
            throw new WorldWalkerException(
                    "dynamic-region grid was truncated by the producer; cannot path in this scene");
        }
        DynamicRegion stable = DynamicRegion.copyOfStable(snap).orElseThrow(() ->
                new WorldWalkerException("dynamic-region grid tore on all "
                        + DynamicRegion.STABLE_COPY_ATTEMPTS
                        + " copy attempts; refusing to plan against static collision"
                        + " inside an instance"));
        log.info("ww readInstance: mode={} origin=({},{}) mapsquares grid={}x{} chunks count={}",
                stable.sceneMode(), stable.originMapX(), stable.originMapY(),
                stable.gridW(), stable.gridH(), stable.chunkCount());
        return stable;
    }

    @Override
    public int readItemCount(int itemId) {
        int n = readItemCountImpl(itemId);
        if (n > 0) {
            log.info("ww readItemCount({}) = {}", itemId, n);
        }
        return n;
    }

    @Override
    public void readVarbits(int[] ids, int[] outValues) {
        // Single batched call replaces N sequential get_varp pipe round-trips.
        // api.readVarbits resolves each varbit's def locally from the cache,
        // groups by varp/varc base, and issues one RPC per domain per 256 distinct
        // bases, however many varbits are passed in.
        if (ids.length == 0) {
            return;
        }
        try {
            List<Integer> idList = new ArrayList<>(ids.length);
            for (int id : ids) {
                idList.add(id);
            }
            List<VarbitRead> results = api.readVarbits(idList);
            // readVarbits preserves input order (one entry per input id), so a
            // size mismatch is a host-side bug; defend with a zero-fill rather
            // than a partial write that mis-pairs ids and values.
            if (results.size() != ids.length) {
                log.warn("ww readVarbits: readVarbits returned {} entries for {} ids",
                        results.size(), ids.length);
                return;
            }
            for (int i = 0; i < ids.length; i++) {
                outValues[i] = walkerValue(results.get(i));
            }
        } catch (RuntimeException e) {
            log.debug("readVarbits({} ids) failed: {}", ids.length, e.toString());
            // outValues already zero-initialised by the executor on the C side;
            // leaving it alone yields the same "all-zero / not present" view
            // the scalar fallback would on per-id exception.
        }
    }

    /** The value to hand the walker: the decoded bits, or 0 ("not present") when there are none. */
    private static int walkerValue(VarbitRead read) {
        return read.hasValue() ? read.value() : 0;
    }

    @Override
    public void readItemCounts(int[] ids, int[] outValues) {
        // Pull both inventories once and walk them into a Map<itemId, total>
        // instead of doing two byInvId+ArrayList rebuilds per id. For the
        // typical ~60 requirement items this collapses ~120 inventory scans
        // into 2 (and the per-id work to a HashMap.get).
        if (ids.length == 0) {
            return;
        }
        try {
            Map<Integer, Integer> totals = new HashMap<>();
            sumIntoMap(Equipment.INVENTORY_ID, totals);
            sumIntoMap(Backpack.INVENTORY_ID, totals);
            for (int i = 0; i < ids.length; i++) {
                Integer total = totals.get(ids[i]);
                outValues[i] = total == null ? 0 : total;
            }
        } catch (RuntimeException e) {
            log.debug("readItemCounts({} ids) failed: {}", ids.length, e.toString());
        }
    }

    private void sumIntoMap(int invId, Map<Integer, Integer> out) {
        GameSnapshot snap = snapshotSource.get();
        if (snap == null) {
            return;
        }
        Optional<Inventory> inv = snap.inventories().byInvId(invId);
        if (inv.isEmpty()) {
            return;
        }
        for (InventoryItem it : inv.get().items()) {
            int id = it.itemId();
            if (id <= 0) {
                continue;  // empty slot
            }
            out.merge(id, it.quantity(), Integer::sum);
        }
    }

    private int readItemCountImpl(int itemId) {
        // Total held = worn (equipment, inv 94) + carried (backpack, inv 93).
        // Used to gate item-requirement teleports (e.g. a dungeoneering cape).
        // Must never throw: the snapshot inventory accessors raise
        // IndexOutOfBoundsException on a mid-update / absent inventory, and any
        // exception escaping a callback is recorded by the upcall stub and
        // cancels the entire run (shouldCancel trips) — so a transient inventory
        // read would silently kill an otherwise-fine teleport before any action
        // fires. Swallow to 0, exactly like readVarbit.
        try {
            return containerCount(Equipment.INVENTORY_ID, itemId)
                    + containerCount(Backpack.INVENTORY_ID, itemId);
        } catch (RuntimeException e) {
            log.debug("readItemCount({}) failed: {}", itemId, e.toString());
            return 0;
        }
    }

    @Override
    public boolean isItemWorn(int itemId) {
        try {
            boolean worn = containerCount(Equipment.INVENTORY_ID, itemId) > 0;
            log.info("ww isItemWorn({}) = {}", itemId, worn);
            return worn;
        } catch (RuntimeException e) {
            log.info("ww isItemWorn({}) failed: {}", itemId, e.toString());
            return false;
        }
    }

    // Live backpack slot holding itemId, or -1 if absent / no snapshot / read
    // failure. The dungeoneering-cape click_item step addresses the item by
    // slot, which is dynamic, so it must be resolved at click time, not baked.
    private int backpackSlotOf(int itemId) {
        try {
            GameSnapshot snap = snapshotSource.get();
            if (snap == null) {
                return -1;
            }
            return snap.inventories().byInvId(Backpack.INVENTORY_ID)
                    .flatMap(inv -> inv.items().stream()
                            .filter(it -> it.itemId() == itemId)
                            .map(InventoryItem::slot)
                            .findFirst())
                    .orElse(-1);
        } catch (RuntimeException e) {
            log.debug("backpackSlotOf({}) failed: {}", itemId, e.toString());
            return -1;
        }
    }

    // Sum of stack quantities of itemId in the given inventory container, read
    // from the live snapshot. Zero when the snapshot or container is absent.
    // May throw (snapshot accessors are index-checked) — callers swallow.
    private int containerCount(int invId, int itemId) {
        GameSnapshot snap = snapshotSource.get();
        if (snap == null) {
            return 0;
        }
        return snap.inventories().byInvId(invId)
                .map(inv -> inv.items().stream()
                        .filter(it -> it.itemId() == itemId)
                        .mapToInt(InventoryItem::quantity)
                        .sum())
                .orElse(0);
    }

    @Override
    public boolean isInterfaceOpen(int interfaceId) {
        // Backed by the v14 SHM open-subs snapshot — membership in the
        // open-subs hashmap is the engine's canonical "this interface is
        // open right now" signal. Sub-microsecond linear scan, no RPC.
        // Returns false when the snapshot isn't available yet (pre-login).
        GameSnapshot snap = snapshotSource.get();
        if (snap == null) {
            return false;
        }
        return snap.isInterfaceOpen(interfaceId);
    }

    @Override
    public void walkTo(WwTile target) {
        log.info("ww walkTo ({},{},p{})", target.x(), target.y(), target.plane());
        queueWalk(target);
        // Walk queued first, for both abilities. Surge dashes in current
        // facing, so the engine must start the move and orient the avatar
        // along the path BEFORE surge drains off the queue next tick. Dive
        // lands on the walk's own target tile, so a dive that goes off leaves
        // the walk nothing to do, and one that doesn't (cooldown drift, no
        // line of sight) still has the walk to carry the player.
        maybeFireMovementAbility(target);
    }

    // Best-effort opportunistic Surge / Dive. Every gate is conservative --
    // anything wrong means a silent fall-through, so the plain walk we just
    // queued still runs. Candidates are tried in preference order; the first
    // that is on a bar and off cooldown gets one chance roll, and a failed
    // roll fires nothing rather than falling through to the next ability.
    private void maybeFireMovementAbility(WwTile target) {
        LocalPlayer lp = currentPlayer();
        if (lp == null || lp.plane() != target.plane() || isWithinGoalGuard(lp)) {
            return;
        }
        // An ability clicked mid-animation (a lodestone arrival, say) is
        // accepted by the client but never casts, and still costs us the
        // re-fire floor. Only fire from idle.
        if (lp.animationId() != NO_ANIMATION) {
            return;
        }
        int dx = target.x() - lp.tileX();
        int dy = target.y() - lp.tileY();
        long now = clockMs.getAsLong();
        for (AbilitySlot slot : candidates(dx, dy, lp)) {
            Optional<BarSlot> bar = readySlot(slot, now);
            if (bar.isEmpty()) {
                continue;
            }
            if (rng.nextDouble() >= slot.family().fireChance()) {
                log.debug("ww {} eligible toward ({},{}) but skipped by chance roll",
                        slot.family().name(), target.x(), target.y());
                return;
            }
            fireAbility(slot, bar.get(), target, now);
            resumeWalkAfterAbility(target);
            return;
        }
    }

    // Surge (and Dive) cancel the walk in progress: the avatar arrives and
    // then stands still until the executor's stall re-click, seconds later.
    // Wait a random 1-2 ticks for the ability to go off, then re-queue the
    // same walk so movement carries on. Sleeping through sleepTicks keeps it
    // cancellable; a run cancelled meanwhile queues nothing more.
    private void resumeWalkAfterAbility(WwTile target) {
        sleepTicks(rng.nextInt(RESUME_WALK_MIN_TICKS, RESUME_WALK_MAX_TICKS + 1));
        if (cancel.get()) {
            return;
        }
        log.info("ww walkTo ({},{},p{}) re-queued after ability",
                target.x(), target.y(), target.plane());
        queueWalk(target);
    }

    private void queueWalk(WwTile target) {
        api.queueAction(new GameAction(ActionTypes.WALK, 1, target.x(), target.y()));
    }

    // Surge moves 10 tiles and Dive moves the avatar off the walk the
    // executor is timing. Within ~12 of the goal, skip: overshooting forces a
    // re-plan that costs more than the walk would have.
    private boolean isWithinGoalGuard(LocalPlayer lp) {
        if (goal == null || goal.plane() != lp.plane()) {
            return false;
        }
        int distToGoal = Math.max(Math.abs(goal.x() - lp.tileX()),
                                  Math.abs(goal.y() - lp.tileY()));
        return distToGoal < ABILITY_GOAL_GUARD;
    }

    // The abilities this hop qualifies for, most preferred first: Dive for a
    // short hop it can reach, then Surge for a long near-straight one.
    private List<AbilitySlot> candidates(int dx, int dy, LocalPlayer lp) {
        List<AbilitySlot> out = new ArrayList<>(2);
        if (isDiveHop(dx, dy)) {
            out.add(diveSlot);
        }
        if (isSurgeHop(dx, dy) && magicLevel(lp) >= SURGE_MIN_MAGIC) {
            out.add(surgeSlot);
        }
        return out;
    }

    /** True when {@code (dx, dy)} is a hop Dive can land: 6..10 tiles, Chebyshev. */
    static boolean isDiveHop(int dx, int dy) {
        int dist = Math.max(Math.abs(dx), Math.abs(dy));
        return dist >= DIVE_MIN_TILES && dist <= DIVE_MAX_TILES;
    }

    /**
     * True when {@code (dx, dy)} is worth a Surge: at least 8 tiles, Chebyshev, and near-straight
     * so the fixed-direction dash stays on the path.
     */
    static boolean isSurgeHop(int dx, int dy) {
        int dist = Math.max(Math.abs(dx), Math.abs(dy));
        return dist >= SURGE_MIN_TILES && isNearStraight(dx, dy);
    }

    /**
     * True when the vector {@code (dx, dy)} lies close to one of the 8 compass directions:
     * near-cardinal when the minor axis is within {@code major / SURGE_STRAIGHT_TOLERANCE_DIVISOR},
     * near-diagonal when the two axes differ by no more than that. Exact cardinals and exact
     * diagonals always pass.
     */
    static boolean isNearStraight(int dx, int dy) {
        int adx = Math.abs(dx);
        int ady = Math.abs(dy);
        int major = Math.max(adx, ady);
        int minor = Math.min(adx, ady);
        boolean isNearCardinal = minor * SURGE_STRAIGHT_TOLERANCE_DIVISOR <= major;
        boolean isNearDiagonal = (major - minor) * SURGE_STRAIGHT_TOLERANCE_DIVISOR <= major;
        return isNearCardinal || isNearDiagonal;
    }

    // The ability's bar slot when it is bound and off cooldown, else empty.
    private Optional<BarSlot> readySlot(AbilitySlot slot, long now) {
        Optional<BarSlot> bar = resolveSlot(slot, now);
        if (bar.isEmpty() || !isOffCooldown(slot, bar.get().spec(), now)) {
            return Optional.empty();
        }
        return bar;
    }

    // Queues the bar click, plus the tile pick for a targeted ability. The
    // click lands on the icon component itself, the same component and shape
    // ComponentNode.interact(1) sends for an action-bar slot.
    private void fireAbility(AbilitySlot slot, BarSlot bar, WwTile target, long now) {
        api.queueAction(new GameAction(ActionTypes.COMPONENT, ABILITY_CLICK_OPTION, NO_SUB_SLOT,
                Interfaces.componentHash(bar.iface(), bar.comp())));
        if (slot.family().isTileTargeted()) {
            api.queueAction(new GameAction(ActionTypes.SELECT_TILE, SELECT_TILE_PARAM,
                    target.x(), target.y()));
        }
        slot.markFired(now);
        log.info("ww {} fired toward ({},{}) via iface={} comp={}",
                bar.spec().name(), target.x(), target.y(), bar.iface(), bar.comp());
    }

    // The ability's bar slot, re-scanning the bars once SLOT_RESCAN_MS has
    // passed since the last scan. A miss is retried after the same backoff
    // rather than latched, and a hit is re-checked on the same schedule:
    // bars and presets change mid-run.
    private Optional<BarSlot> resolveSlot(AbilitySlot slot, long now) {
        if (!slot.isScanDue(now, SLOT_RESCAN_MS)) {
            return slot.bound();
        }
        Optional<BarSlot> found = scanBars(slot.family().variants());
        if (slot.recordScan(now, found)) {
            logScanOutcome(slot.family(), found);
        }
        return found;
    }

    // Logs a scan whose outcome differs from the previous one, so a steady
    // hit or miss is reported once rather than every minute.
    private static void logScanOutcome(AbilityFamily family, Optional<BarSlot> found) {
        if (found.isPresent()) {
            BarSlot bar = found.get();
            log.info("ww {} slot resolved: {} on iface={} comp={}",
                    family.name(), bar.spec().name(), bar.iface(), bar.comp());
        } else {
            log.info("ww {} slot not found on any action bar; re-scanning every {} ms",
                    family.name(), SLOT_RESCAN_MS);
        }
    }

    // First bar slot showing any variant's icon, variants in preference order.
    private Optional<BarSlot> scanBars(List<AbilitySpec> variants) {
        for (AbilitySpec spec : variants) {
            OptionalInt sprite = iconSprite(spec.structId());
            if (sprite.isEmpty()) {
                continue;
            }
            for (int iface : ACTION_BAR_IFACES) {
                ComponentNode node = findSprite(iface, sprite.getAsInt());
                if (node != null) {
                    return Optional.of(new BarSlot(spec, node.interfaceId(), node.componentId()));
                }
            }
        }
        return Optional.empty();
    }

    private ComponentNode findSprite(int iface, int spriteId) {
        try {
            return api.components().in(iface).withSpriteId(spriteId).first();
        } catch (RuntimeException e) {
            log.debug("ww ability slot scan failed on iface={}: {}", iface, e.toString());
            return null;
        }
    }

    // The bar icon sprite named by a struct's icon param, or empty when the
    // cache can't answer (no cache attached, unknown struct, no such param).
    private OptionalInt iconSprite(int structId) {
        Integer cached = iconSpriteByStruct.get(structId);
        if (cached != null) {
            return OptionalInt.of(cached);
        }
        StructType struct;
        try {
            struct = api.getStructType(structId);
        } catch (RuntimeException e) {
            log.debug("ww struct {} unreadable: {}", structId, e.toString());
            return OptionalInt.empty();
        }
        if (struct == null || struct.params() == null) {
            return OptionalInt.empty();
        }
        int sprite = MapHelper.getIntOr(struct.params(), STRUCT_PARAM_ICON_SPRITE, NO_SPRITE);
        if (sprite == NO_SPRITE) {
            return OptionalInt.empty();
        }
        iconSpriteByStruct.put(structId, sprite);
        return OptionalInt.of(sprite);
    }

    // Off cooldown when past the wall-clock re-fire floor AND the ability's
    // cooldown-end varc is at or behind the client game cycle (the snapshot's
    // gameCycle, the same counter the get_game_cycle RPC reads). An unset
    // varc reads -1, so an ability never used this session reads ready. A
    // failed read counts as "on cooldown".
    private boolean isOffCooldown(AbilitySlot slot, AbilitySpec spec, long now) {
        if (slot.isWithinRefireFloor(now, spec.refireFloorMs())) {
            return false;
        }
        GameSnapshot snap = snapshotSource.get();
        if (snap == null) {
            return false;
        }
        try {
            return api.getVarcInt(spec.cooldownEndVarc()) <= snap.gameCycle();
        } catch (RuntimeException e) {
            log.debug("ww {} cooldown varc {} unreadable: {}",
                    spec.name(), spec.cooldownEndVarc(), e.toString());
            return false;
        }
    }

    private static int magicLevel(LocalPlayer lp) {
        for (Skill s : lp.skills()) {
            if (s.typeId() == MAGIC_SKILL_TYPE) {
                return s.actualLevel();
            }
        }
        return 0;
    }

    @Override
    public int interact(int objectId, WwTile tile, int optionIndex) {
        if (optionIndex < 0 || optionIndex + 1 >= ActionTypes.OBJECT_OPTIONS.length) {
            log.warn("interact: option index {} out of range for loc {}", optionIndex, objectId);
            return 0;
        }
        // The NXT engine's object DoAction takes (locTypeId, worldX, worldY) —
        // the same shape a manual click emits. We deliberately do NOT use the
        // scene interact handle: doors and stairs are published as
        // COMBINED_LOCATION_SECTIONs, which always carry interactId == -1, so a
        // handle lookup can never resolve them. We only need the loc's true
        // tile, which sections do carry. The transition's row tile is often the
        // stand tile or a corner of a multi-tile loc rather than its anchor, so
        // we match against the loc's whole footprint (see resolveLocTile).
        WwTile locTile = resolveLocTile(objectId, tile);
        if (locTile == null) {
            // The baked transition names the CLOSED loc (the world-map door, or
            // the stairs/ladder object). When that loc is absent from the live
            // scene at the interact tile the obstacle is no longer there to act
            // on: an open door is a different loc id, so a door we've already
            // opened (or that spawned open) simply isn't found. Treat it as
            // already-traversable and skip the interact — the executor advances
            // to the next step, whose walk routes straight through the open
            // doorway. (A plane-change loc can't be "open"; if a stairs loc were
            // ever missing the following different-plane walk would stall and
            // trigger a re-plan, which is the correct failure mode, not a clip.)
            log.info("interact: loc {} absent at ({},{},{}); assuming already open/removed, skipping",
                    objectId, tile.x(), tile.y(), tile.plane());
            return 0;
        }
        int actionId = ActionTypes.OBJECT_OPTIONS[optionIndex + 1];
        api.queueAction(new GameAction(actionId, objectId, locTile.x(), locTile.y()));
        return 1;
    }

    @Override
    public void runChainStep(int kind, int a, int b, int c, int d,
                             int e, int f, int g, int h, int i) {
        // The executor resolves Wait / WaitInterface itself and pre-resolves a
        // ClickItem's worn-vs-backpack variant; only these kinds reach us.
        // DialogueAnswer is never baked into a chain: the executor sends it
        // itself when an option list is open inside a dialog zone.
        switch (ChainStepKind.fromWire(kind)) {
            case CLICK -> {
                // Generic ready-to-queue action: a=actionId, b..d=param1..3.
                // For a component click that is (COMPONENT, option, sub,
                // (iface<<16)|comp); the executor already gated the interface.
                log.info("ww runChainStep CLICK: action id={} p1={} p2={} p3={} (iface={})",
                        a, b, c, d, d >>> 16);
                api.queueAction(new GameAction(a, b, c, d));
            }
            case CLICK_ITEM -> {
                // Executor chose the variant: a=iface, b=comp, c=option, d=sub
                // (slot fallback), e=special, f=carried item id. Map special to
                // COMPONENT_SPECIAL, else COMPONENT. For the backpack variant
                // (f != 0) the item's slot is dynamic, so resolve it live; the
                // baked d is only used if the item isn't found.
                int actionId = e != 0 ? ActionTypes.COMPONENT_SPECIAL : ActionTypes.COMPONENT;
                int hash = (a << 16) | (b & 0xFFFF);
                int subComponent = d;
                if (f != 0) {
                    int slot = backpackSlotOf(f);
                    if (slot >= 0) {
                        subComponent = slot;
                    } else {
                        log.warn("runChainStep CLICK_ITEM: item {} not in backpack; "
                                + "falling back to baked slot {}", f, d);
                    }
                }
                log.info("ww runChainStep CLICK_ITEM: actionId={} iface={} comp={} opt={} "
                        + "sub(slot)={} special={} item={} -> hash={}",
                        actionId, a, b, c, subComponent, e, f, hash);
                api.queueAction(new GameAction(actionId, c, subComponent, hash));
            }
            case DIALOGUE_SELECT -> dispatchDialogueSelect(a, b, c, d);
            case DIALOGUE_ANSWER ->
                answerDialogue(DialogueAnswerText.decode(a, b, c, d, e, f, g, h, i));
            case CLICK_NPC -> clickNpc(a, new WwTile(b, c, d), e, f, g);
            default ->
                // Wait / WaitInterface are handled executor-side and never sent
                // here; a stray one is a producer/consumer drift — log, ignore.
                log.warn("runChainStep: unexpected host-side kind {} (ignored)", kind);
        }
    }

    // Standard RS3 dialogue option component ids for the first nine options on a
    // page. Mirrors the prior nav stack's DIALOGUE_SELECT mapping.
    private static final int[] DIALOGUE_OPTION_COMPS = {1, 20, 23, 26, 29, 32, 35, 38, 41};

    // Select option `index` in dialogue interface `iface`. perPage/nextComp drive
    // multi-page dialogues; most teleport dialogues are single-page (index <
    // perPage), the only case exercised today.
    private void dispatchDialogueSelect(int iface, int index, int perPage, int nextComp) {
        int pp = perPage > 0 ? perPage : DIALOGUE_OPTION_COMPS.length;
        int targetPage = index / pp;
        int optionOnPage = index % pp;
        // Advance to the target page first (best-effort, no inter-click wait —
        // single-page is the common path and needs none).
        for (int p = 0; p < targetPage; p++) {
            api.queueAction(new GameAction(ActionTypes.DIALOGUE, 0, -1, (iface << 16) | nextComp));
        }
        int comp = optionOnPage < DIALOGUE_OPTION_COMPS.length
                ? DIALOGUE_OPTION_COMPS[optionOnPage] : 1;
        log.info("ww runChainStep DIALOGUE_SELECT: iface={} index={} -> page={} comp={} hash={}",
                iface, index, targetPage, comp, (iface << 16) | comp);
        api.queueAction(new GameAction(ActionTypes.DIALOGUE, 0, -1, (iface << 16) | comp));
    }

    // The origin of a transition with no loc (a charter ship's crewmember). An
    // NPC action targets a live server index, so it can't be baked into a CLICK;
    // resolve the nearest NPC with typeId in [firstTypeId, lastTypeId] on the
    // centre's plane within Chebyshev `radius`. No NPC (or a bad option) is a
    // no-op: the executor's next WaitInterface times out and the transition is
    // reported as a missing origin and routed around, like an absent loc.
    private void clickNpc(int optionIndex, WwTile centre, int radius,
                          int firstTypeId, int lastTypeId) {
        if (optionIndex < 0 || optionIndex + 1 >= ActionTypes.NPC_OPTIONS.length) {
            log.warn("ww runChainStep CLICK_NPC: option index {} out of range for npc {}..{}",
                    optionIndex, firstTypeId, lastTypeId);
            return;
        }
        Npc npc = resolveNpc(centre, radius, firstTypeId, lastTypeId);
        if (npc == null) {
            log.info("ww runChainStep CLICK_NPC: no npc {}..{} within {} of ({},{},{}), nothing clicked",
                    firstTypeId, lastTypeId, radius, centre.x(), centre.y(), centre.plane());
            return;
        }
        log.info("ww runChainStep CLICK_NPC: npc {} (index {}) at {},{} op {}",
                npc.typeId(), npc.serverIndex(), npc.tileX(), npc.tileY(), optionIndex);
        api.queueAction(new GameAction(
                ActionTypes.NPC_OPTIONS[optionIndex + 1], npc.serverIndex(), 0, 0));
    }

    // Pick the first open option whose text contains `answer` (case-insensitive,
    // markup-stripped: Dialog.select). No list open, or no option matching, is a
    // no-op: the executor tries the zone's next answer on its next poll.
    private void answerDialogue(String answer) {
        if (answer.isEmpty()) {
            log.info("ww runChainStep DIALOGUE_ANSWER: empty answer, nothing selected");
            return;
        }
        boolean isSelected = Dialog.select(api, answer);
        if (isSelected) {
            log.info("ww runChainStep DIALOGUE_ANSWER: selected option containing '{}'", answer);
        } else {
            log.info("ww runChainStep DIALOGUE_ANSWER: no open option contains '{}', nothing selected",
                    answer);
        }
    }

    @Override
    public void sleepTicks(int ticks) {
        if (ticks <= 0) {
            return;
        }
        try {
            sleeper.sleep(ticks * TICK_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel.set(true);
            throw new WorldWalkerException("sleepTicks interrupted", e);
        }
    }

    @Override
    public boolean shouldCancel() {
        return cancel.get();
    }

    @Override
    public void onEvent(WwEvent event) {
        eventSink.accept(event);
    }

    private LocalPlayer currentPlayer() {
        GameSnapshot snap = snapshotSource.get();
        return snap == null ? null : snap.self();
    }

    /**
     * The tile of the placed loc a transition names, or {@code null} when no such loc is in the
     * scene near the transition's row tile.
     *
     * <p>A candidate is a loc on the row tile's plane whose type or morph-resolved id is the
     * transition's object id and which is not flagged deleted. Doors and stairs arrive as
     * combined-location sections ({@code interactId == -1}), so neither {@code interactId} nor
     * the section flag is filtered on. The resolvedId arm is additive: a morph ("multiloc") loc
     * answers to both its base and its resolved id.</p>
     *
     * <p>Distance is measured to the loc's footprint, not its anchor tile: the dataset's row tile
     * is often the stand tile or a corner of a multi-tile loc (a 4x4 cave entrance anchored two
     * tiles from its row). A footprint within {@link #LOC_FOOTPRINT_REACH} is the normal case;
     * anything out to {@link #LOC_FALLBACK_REACH} is still accepted, closest footprint first and
     * then closest anchor, so an exact-tile loc beats a neighbour. The returned tile is always the
     * loc's own anchor, which is what the {@code (typeId, worldX, worldY)} action targets.</p>
     */
    private WwTile resolveLocTile(int objectId, WwTile tile) {
        GameSnapshot snap = snapshotSource.get();
        if (snap == null) {
            return null;
        }
        List<Location> candidates = snap.locations().stream()
                .filter(loc -> (loc.typeId() == objectId || loc.resolvedId() == objectId)
                        && loc.plane() == tile.plane()
                        && !loc.isDeleted())
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        LocSize size = locSize(objectId);
        Optional<LocMatch> best = candidates.stream()
                .map(loc -> LocMatch.of(loc, size, tile))
                .filter(match -> match.footprintDistance() <= LOC_FALLBACK_REACH)
                .min(Comparator.comparingInt(LocMatch::footprintDistance)
                        .thenComparingInt(LocMatch::anchorDistance));
        best.ifPresent(match -> logOffsetMatch(objectId, tile, match));
        return best.map(match -> new WwTile(match.loc().tileX(), match.loc().tileY(),
                match.loc().plane())).orElse(null);
    }

    /**
     * The cache size of loc type {@code objectId}, or 1x1 when the cache cannot answer (no cache
     * attached, a failed read, or an unknown id). A 1x1 guess degrades to anchor-tile matching,
     * which the fallback reach still covers.
     */
    private LocSize locSize(int objectId) {
        LocationType type;
        try {
            type = api.getLocationType(objectId);
        } catch (RuntimeException e) {
            log.debug("interact: size of loc {} unreadable ({}); assuming 1x1",
                    objectId, e.toString());
            return LocSize.UNKNOWN;
        }
        return type == null ? LocSize.UNKNOWN : new LocSize(type.sizeX(), type.sizeY());
    }

    /** Logs a match whose anchor tile is not the transition's row tile. */
    private static void logOffsetMatch(int objectId, WwTile tile, LocMatch match) {
        if (match.anchorDistance() == 0) {
            return;
        }
        Location loc = match.loc();
        LocFootprint fp = match.footprint();
        String reach = match.footprintDistance() <= LOC_FOOTPRINT_REACH ? "footprint" : "fallback";
        log.info("interact: loc {} matched at ({},{},{}) for row tile ({},{},{}); {}x{} footprint"
                        + " {} tile(s) away ({} reach)",
                objectId, loc.tileX(), loc.tileY(), loc.plane(), tile.x(), tile.y(), tile.plane(),
                fp.width(), fp.depth(), match.footprintDistance(), reach);
    }

    /** Blocks the calling thread for a number of milliseconds. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * The bridge's sources of randomness, time and sleep, injectable so tests can fix them.
     *
     * @param rng     movement-ability chance rolls and post-ability pauses
     * @param clockMs epoch milliseconds, for cooldown floors and bar re-scans
     * @param sleeper what {@link #sleepTicks} blocks on
     */
    record Pacing(RandomGenerator rng, LongSupplier clockMs, Sleeper sleeper) {
        Pacing {
            Objects.requireNonNull(rng, "rng");
            Objects.requireNonNull(clockMs, "clockMs");
            Objects.requireNonNull(sleeper, "sleeper");
        }

        /** A fresh default generator, the system clock and {@link Thread#sleep(long)}. */
        static Pacing system() {
            return new Pacing(RandomGenerator.getDefault(), System::currentTimeMillis,
                    Thread::sleep);
        }
    }

    /**
     * One movement-ability variant.
     *
     * @param name            display name, for logs
     * @param structId        the ability's cache struct, whose icon param names its bar sprite
     * @param cooldownEndVarc varc holding the game cycle its cooldown ends on
     * @param refireFloorMs   least wall-clock time between two fires, whatever the varc says
     */
    record AbilitySpec(String name, int structId, int cooldownEndVarc, long refireFloorMs) {
    }

    /**
     * A movement ability as the walker uses it: its variants in bar-scan preference order, the
     * chance an eligible walk fires it, and whether firing it needs a tile picked afterwards.
     */
    private record AbilityFamily(String name, List<AbilitySpec> variants, double fireChance,
                                 boolean isTileTargeted) {
    }

    /** Where a variant's icon sits: the component to click. */
    private record BarSlot(AbilitySpec spec, int iface, int comp) {
    }

    /** Per-run bar-slot and fire state for one {@link AbilityFamily}. */
    private static final class AbilitySlot {
        private final AbilityFamily family;
        private Optional<BarSlot> bound = Optional.empty();
        private long lastScanMs = NEVER_MS;
        private long lastFireMs = NEVER_MS;

        AbilitySlot(AbilityFamily family) {
            this.family = family;
        }

        AbilityFamily family() {
            return family;
        }

        Optional<BarSlot> bound() {
            return bound;
        }

        boolean isScanDue(long now, long rescanMs) {
            return lastScanMs == NEVER_MS || now - lastScanMs >= rescanMs;
        }

        /** Stores a scan result; true when it differs from the last one (or is the first). */
        boolean recordScan(long now, Optional<BarSlot> found) {
            boolean isChanged = lastScanMs == NEVER_MS || !bound.equals(found);
            lastScanMs = now;
            bound = found;
            return isChanged;
        }

        boolean isWithinRefireFloor(long now, long floorMs) {
            return lastFireMs != NEVER_MS && now - lastFireMs < floorMs;
        }

        void markFired(long now) {
            lastFireMs = now;
        }
    }

    /** A loc type's cache dimensions, before rotation. */
    private record LocSize(int sizeX, int sizeY) {
        /** The size assumed when the cache cannot say: the anchor tile alone. */
        static final LocSize UNKNOWN =
                new LocSize(LocFootprint.UNKNOWN_SIZE, LocFootprint.UNKNOWN_SIZE);
    }

    /**
     * A candidate loc measured against a transition's row tile.
     *
     * @param loc               the placed loc
     * @param footprint         the tiles it covers at its rotation
     * @param footprintDistance Chebyshev distance from the row tile to the footprint
     * @param anchorDistance    Chebyshev distance from the row tile to the loc's anchor tile
     */
    private record LocMatch(Location loc, LocFootprint footprint, int footprintDistance,
                            int anchorDistance) {

        /** Measures {@code loc}, of cache size {@code size}, against {@code tile}. */
        static LocMatch of(Location loc, LocSize size, WwTile tile) {
            LocFootprint fp = LocFootprint.of(loc.tileX(), loc.tileY(), size.sizeX(), size.sizeY(),
                    loc.rotation());
            int anchor = Math.max(Math.abs(loc.tileX() - tile.x()),
                    Math.abs(loc.tileY() - tile.y()));
            return new LocMatch(loc, fp, fp.distanceTo(tile.x(), tile.y()), anchor);
        }
    }

    private static int chebyshev(Npc npc, WwTile tile) {
        return Math.max(Math.abs(npc.tileX() - tile.x()), Math.abs(npc.tileY() - tile.y()));
    }

    private Npc resolveNpc(WwTile centre, int radius, int firstTypeId, int lastTypeId) {
        GameSnapshot snap = snapshotSource.get();
        if (snap == null) {
            return null;
        }
        return snap.npcs().stream()
                .filter(npc -> npc.typeId() >= firstTypeId
                        && npc.typeId() <= lastTypeId
                        && npc.plane() == centre.plane()
                        && chebyshev(npc, centre) <= radius)
                .min(Comparator.comparingInt(npc -> chebyshev(npc, centre)))
                .orElse(null);
    }
}
