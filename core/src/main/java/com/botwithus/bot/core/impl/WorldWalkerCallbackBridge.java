package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.component.ComponentNode;
import com.botwithus.bot.api.dialog.Dialog;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.inventory.Equipment;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.LocationType;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    // Opportunistic Surge config. Surge launches the avatar 10 tiles forward in
    // its current facing; we piggy-back it onto walkTo when the next chunk is a
    // straight ≥8-tile run and we're not within overshoot range of the final
    // goal. Detection is by sprite scan over the active action-bar interface —
    // sprite 14659 is Surge's canonical icon and is unique across slots.
    private static final int  SURGE_SPRITE_ID    = 14659;
    private static final int  MAGIC_SKILL_TYPE   = 6;     // StatType.id for Magic
    private static final int  SURGE_MIN_MAGIC    = 24;    // ability unlock level
    private static final long SURGE_COOLDOWN_MS  = 17_000L;
    private static final int  SURGE_MIN_TILES    = 8;     // don't burn cooldown on short hops
    private static final int  SURGE_GOAL_GUARD   = 12;    // skip if close enough to overshoot
    // Candidate action-bar interfaces, scanned in priority order. 1670 is the
    // NIS modern bar; 1430 is the legacy one. Both expose ability slot sprites
    // at the same offset structure.
    private static final int[] ACTION_BAR_IFACES = { 1670, 1430 };

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

    // Surge slot cache: resolved once per run on the first eligible walkTo, then
    // reused. -1 in surgeIface means "not yet attempted"; SURGE_DISABLED in it
    // means "we looked, didn't find the icon — give up for this run".
    private static final int SURGE_NOT_RESOLVED = -1;
    private static final int SURGE_DISABLED     = -2;
    private int  surgeIface  = SURGE_NOT_RESOLVED;
    private int  surgeComp   = SURGE_NOT_RESOLVED;
    private long lastSurgeMs = 0L;

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
        api.queueAction(new GameAction(ActionTypes.WALK, 1, target.x(), target.y()));
        // Walk queued first so the engine starts the move + orients the avatar
        // along the path BEFORE surge drains off the queue next tick — surge
        // dashes in current facing, so the order matters.
        maybeFireSurge(target);
    }

    // Best-effort opportunistic Surge. Every gate is conservative — anything
    // wrong → silent fall-through so the plain walk we just queued still runs.
    private void maybeFireSurge(WwTile target) {
        long now = System.currentTimeMillis();
        if (now - lastSurgeMs < SURGE_COOLDOWN_MS) {
            return;
        }
        LocalPlayer lp = currentPlayer();
        if (lp == null || lp.plane() != target.plane()) {
            return;
        }
        if (magicLevel(lp) < SURGE_MIN_MAGIC) {
            return;
        }
        int dx   = target.x() - lp.tileX();
        int dy   = target.y() - lp.tileY();
        int adx  = Math.abs(dx);
        int ady  = Math.abs(dy);
        int dist = Math.max(adx, ady);
        if (dist < SURGE_MIN_TILES) {
            return;
        }
        // 8-way straight: pure cardinal (one axis is 0) OR pure diagonal (axes equal).
        // Surge fires 10 tiles in one direction, so any path bend wastes the cooldown.
        boolean straight = (dx == 0) || (dy == 0) || (adx == ady);
        if (!straight) {
            return;
        }
        if (goal != null && goal.plane() == lp.plane()) {
            int distToGoal = Math.max(Math.abs(goal.x() - lp.tileX()),
                                      Math.abs(goal.y() - lp.tileY()));
            // Surge moves 10 tiles. If we're already within ~12 of the goal,
            // skip — overshooting forces a re-plan that costs more than the
            // walk would have.
            if (distToGoal < SURGE_GOAL_GUARD) {
                return;
            }
        }
        if (!resolveSurgeSlot()) {
            return;
        }
        api.queueAction(new GameAction(
                ActionTypes.COMPONENT,
                /* option= */ 1,
                /* sub= */    -1,
                Interfaces.componentHash(surgeIface, surgeComp)));
        lastSurgeMs = now;
        log.info("ww surge fired toward ({},{}) dist={} via iface={} comp={}",
                target.x(), target.y(), dist, surgeIface, surgeComp);
    }

    // True when Surge's click target is known. Scans candidate bars on first
    // call; latches to disabled if Surge isn't bound on the active bar so the
    // next walkTo doesn't pay the RPC again.
    private boolean resolveSurgeSlot() {
        if (surgeIface == SURGE_DISABLED) {
            return false;
        }
        if (surgeIface != SURGE_NOT_RESOLVED) {
            return true;
        }
        for (int iface : ACTION_BAR_IFACES) {
            ComponentNode sprite;
            try {
                sprite = api.components().in(iface).withSpriteId(SURGE_SPRITE_ID).first();
            } catch (RuntimeException e) {
                log.debug("ww surge slot scan failed on iface={}: {}", iface, e.toString());
                continue;
            }
            if (sprite != null) {
                // Slot layout: ability sprite at comp N, click-target Box at comp N+1.
                surgeIface = iface;
                surgeComp  = sprite.componentId() + 1;
                log.info("ww surge slot resolved: iface={} sprite_comp={} click_comp={}",
                        iface, sprite.componentId(), surgeComp);
                return true;
            }
        }
        log.info("ww surge slot not found on any candidate bar; disabling for this run");
        surgeIface = SURGE_DISABLED;
        return false;
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
            Thread.sleep(ticks * TICK_MS);
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
