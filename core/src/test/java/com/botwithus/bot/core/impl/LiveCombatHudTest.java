package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.combat.Ability;
import com.botwithus.bot.api.combat.ActionBarSlot;
import com.botwithus.bot.api.combat.ActionBar;
import com.botwithus.bot.api.combat.Cooldown;
import com.botwithus.bot.api.combat.LocalCombat;
import com.botwithus.bot.api.combat.Prayer;
import com.botwithus.bot.api.diag.StubGuard;
import com.botwithus.bot.api.event.GameEvent;
import com.botwithus.bot.api.event.InventoryChangeEvent;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.util.Interfaces;
import com.botwithus.bot.api.world.Lodestone;
import com.botwithus.bot.api.world.LodestoneNetwork;
import com.botwithus.bot.core.cache.NXTCache;
import com.botwithus.bot.core.gameval.SqliteGamevalIndex;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.pipe.AgentSelection;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.shm.SharedRegion;
import com.botwithus.bot.core.shm.SharedRegionEventPump;
import com.botwithus.bot.core.test.LiveAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Action bar, prayer, cooldown units, inventory change events and lodestones against a
 * live client.</b>
 *
 * <p>Opt in with {@code -Dbotwithus.live.combatHud=true} on top of {@code :core:harnessTest};
 * the lodestone round trip additionally needs {@code -Dbotwithus.live.lodestones=true}
 * because it moves the character between towns. Every check logs what it saw, so the run's
 * output is the evidence. A check whose precondition the account does not meet (no
 * prayer points, no ability on the bar, fewer than two unlocked lodestones) is skipped
 * with that reason, never passed.</p>
 *
 * <p>The cooldown check runs the client scripts 8247 and 6506 through
 * {@code execute_script}; both only read client variables.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
@EnabledIfSystemProperty(named = "botwithus.live.combatHud", matches = "true")
class LiveCombatHudTest {

    private static final Logger log = LoggerFactory.getLogger(LiveCombatHudTest.class);

    private static final String LODESTONES_PROPERTY = "botwithus.live.lodestones";
    /** {@code COMBATV2_REQUIRES_TARGET_FOE}: 0 for an ability that fires with no target. */
    private static final int PARAM_REQUIRES_TARGET = 3394;
    private static final int GROUND_TAKE_OP = 3;
    /** Defence ability 1: no target, 41-tick cooldown, Defence 3. */
    private static final String ANTICIPATION = "Anticipation";
    /** A plain log: droppable with no confirmation, and the fixture backpack carries some. */
    private static final int LOGS = 1511;
    /** The combat bar's orb text layers: prayer, summoning, health. */
    private static final int COMBAT_BAR = 1430;
    private static final int PRAYER_ORB = 14;
    private static final int SUMMONING_ORB = 20;
    private static final int HEALTH_ORB = 7;
    /** The ribbon (1431:0) and the prayer window's slot on it, to close a window opened here. */
    private static final int RIBBON_IFACE = 1431;
    private static final int PRAYER_RIBBON_SLOT = 11;
    private static final String DROP = "Drop";
    private static final long SHORT_TIMEOUT_MS = 6_000;
    private static final long LONG_TIMEOUT_MS = 30_000;
    private static final long POLL_MS = 150;
    private static final long SAMPLE_MS = 1_200;
    private static final int SAMPLES = 4;
    /** Client cycles per server tick: 600 ms / 20 ms. */
    private static final int CYCLES_PER_TICK = 30;

    private SharedRegion region;
    private RpcClient rpc;
    private NXTCache cache;
    private GamevalIndex gamevals;
    private SharedRegionEventPump pump;
    private GameAPI api;
    private final List<GameEvent> events = new CopyOnWriteArrayList<>();

    @BeforeAll
    void connect() throws IOException {
        AgentSelection.Attach agent = LiveAgent.attachOrFail();
        region = SharedRegion.open(agent.pid());
        rpc = new RpcClient(new PipeClient(agent.pipeName()));
        cache = NXTCache.openForHost();
        gamevals = SqliteGamevalIndex.openDefault().<GamevalIndex>map(i -> i)
                .orElseGet(GamevalIndex::empty);
        Assumptions.assumeTrue(gamevals.isAvailable(),
                "SKIPPED, not passed: no gameval index deployed; the action bar resolves by name");
        api = new GameAPIImpl(rpc, cache, () -> new GameSnapshotImpl(region.snapshot()),
                new StubGuard(), event -> { }, gamevals);
        pump = new SharedRegionEventPump(agent.pid(), events::add);
        assertEquals(GameSnapshot.GAME_STATE_IN_GAME, api.snapshot().gameState(),
                "the character must be in the world");
    }

    @AfterAll
    void disconnect() throws Exception {
        if (pump != null) {
            pump.close();
        }
        if (rpc != null) {
            rpc.close();
        }
        if (cache != null) {
            cache.close();
        }
        if (gamevals != null) {
            gamevals.close();
        }
        if (region != null) {
            region.close();
        }
    }

    @Test
    @Order(1)
    void actionBar_readsTheActiveBarsSlots() {
        int bar = api.actionBar().activeBar();
        List<ActionBarSlot> slots = api.actionBar().slots();
        log.info("combat hud live: active bar {} ({} slots)", bar, slots.size());
        slots.forEach(slot -> log.info("combat hud live:   {}", slot));

        for (int other = 1; other <= ActionBar.MAX_BAR; other++) {
            List<ActionBarSlot> content = api.actionBar().slotsOf(other).stream()
                    .filter(s -> !(switch (s) {
                        case ActionBarSlot.Empty e -> true;
                        case ActionBarSlot.AbilitySlot a -> false;
                        case ActionBarSlot.ItemSlot i -> false;
                        case ActionBarSlot.Unresolved u -> false;
                    })).toList();
            if (!content.isEmpty()) {
                log.info("combat hud live: bar {} holds {}", other, content);
            }
        }
        assertTrue(bar >= 1, "COMBATV2_ACTIONBAR_CURRENT_BAR did not read as a bar number: " + bar);
        assertEquals(14, slots.size());
        Assumptions.assumeTrue(slots.stream().anyMatch(s -> switch (s) {
            case ActionBarSlot.AbilitySlot a -> true;
            case ActionBarSlot.Empty e -> false;
            case ActionBarSlot.ItemSlot i -> false;
            case ActionBarSlot.Unresolved u -> false;
        }), "SKIPPED, not passed: the active bar holds no ability to read");
    }

    @Test
    @Order(2)
    void combatState_pointsMatchTheOrbsText() {
        LocalCombat combat = api.combat();
        log.info("combat hud live: name {}, life {}/{}, prayer {}/{} (raw {}), summoning {}/{}, "
                        + "adrenaline {}, target {}, inCombat {}", combat.name(), combat.lifePoints(),
                combat.maxLifePoints(), api.prayers().points(), api.prayers().maxPoints(),
                api.prayers().rawPoints(), api.summoning().points(), api.summoning().maxPoints(),
                combat.adrenaline(), combat.target(), combat.isInCombat());
        assertEquals(api.prayers().points() + "/" + api.prayers().maxPoints(),
                orbText(PRAYER_ORB), "the prayer orb and Prayers.points()/maxPoints() disagree");
        assertEquals(api.summoning().points() + "/" + api.summoning().maxPoints(),
                orbText(SUMMONING_ORB), "the summoning orb and Summoning.points() disagree");
        assertEquals(String.format("%,d/%,d", combat.lifePoints(), combat.maxLifePoints()),
                orbText(HEALTH_ORB), "the health orb and LocalCombat.lifePoints() disagree");
        assertTrue(combat.name().isPresent(), "the local player's name did not resolve");
    }

    @Test
    @Order(3)
    void prayer_toggledOnAndOff_readsBackThroughIsActive() {
        Prayer prayer = Prayer.TIERED_DEFENCE;
        Assumptions.assumeTrue(api.prayers().points() > 0,
                "SKIPPED, not passed: the character has no prayer points");
        Assumptions.assumeTrue(!api.prayers().isActive(prayer),
                "SKIPPED, not passed: " + prayer + " is already on; turn it off first");
        log.info("combat hud live: book {}, points {}/{}, {} is {}", api.prayers().activeBook(),
                api.prayers().points(), api.prayers().maxPoints(), prayer,
                api.prayers().info(prayer).orElse(null));

        boolean isOpenedHere = !api.prayers().isWindowOpen();
        assertTrue(api.prayers().openWindow());
        assertTrue(waitFor(api.prayers()::isWindowOpen, SHORT_TIMEOUT_MS),
                "the ribbon click did not open the prayer window");
        assertTrue(api.prayers().toggle(prayer), "the prayer window is not open or the button "
                + "for " + prayer + " was not found");
        assertTrue(waitFor(() -> api.prayers().isActive(prayer), SHORT_TIMEOUT_MS),
                prayer + " was clicked but its varbit never read on");
        log.info("combat hud live: {} on; active = {}", prayer, api.prayers().activePrayers());
        assertTrue(api.prayers().toggle(prayer));
        assertTrue(waitFor(() -> !api.prayers().isActive(prayer), SHORT_TIMEOUT_MS),
                prayer + " was clicked again but its varbit never read off");
        log.info("combat hud live: {} off again", prayer);
        if (isOpenedHere) {
            api.queueAction(new GameAction(ActionTypes.COMPONENT, 1, PRAYER_RIBBON_SLOT,
                    Interfaces.componentHash(RIBBON_IFACE, 0)));
        }
    }

    @Test
    @Order(4)
    void cooldownScripts_returnAWindow_forACacheResolvedAbility() {
        Ability anticipation = api.actionBar().catalog().byName(ANTICIPATION).orElse(null);
        assertTrue(anticipation != null, "the cache did not resolve " + ANTICIPATION);
        Cooldown window = api.actionBar().cooldown(anticipation);
        Cooldown global = api.actionBar().globalCooldown();
        int cycle = api.snapshot().gameCycle();
        log.info("combat hud live: {} = {}; scripts 8247/6506 -> {}; global {}; cycle {}, tick {}",
                ANTICIPATION, anticipation, window, global, cycle, api.snapshot().serverTick());

        assertTrue(window.isKnown(), "client scripts 8247/6506 did not return a cooldown window");
        assertTrue(window.endCycle() <= cycle, "an ability this character never used reads as "
                + "on cooldown: " + window + " at cycle " + cycle);
    }

    @Test
    @Order(5)
    void abilityUse_cooldownGoesUpThenCountsDown_inClientCycles() {
        ActionBarSlot.AbilitySlot slot = targetlessAbilityWithCooldown().orElse(null);
        Assumptions.assumeTrue(slot != null,
                "SKIPPED, not passed: no ability on the bar has a cooldown and needs no target");
        Ability ability = slot.ability();
        Cooldown before = api.actionBar().cooldown(ability);
        log.info("combat hud live: using {} (slot {}, cooldown {} ticks); before = {}, cycle {}",
                ability.name(), slot.slot(), ability.cooldownTicks(), before,
                api.snapshot().gameCycle());
        assertTrue(before.isKnown(), "scripts 8247/6506 did not return a cooldown window");
        Assumptions.assumeTrue(before.isReady(api.snapshot().gameCycle()),
                "SKIPPED, not passed: " + ability.name() + " is already on cooldown");

        assertTrue(api.actionBar().useSlot(slot.slot()));
        assertTrue(waitFor(() -> !api.actionBar().cooldown(ability)
                .isReady(api.snapshot().gameCycle()), SHORT_TIMEOUT_MS),
                ability.name() + " was pressed but its cooldown never started");
        List<Integer> remaining = sampleRemaining(ability);

        Cooldown window = api.actionBar().cooldown(ability);
        int length = window.endCycle() - window.startCycle();
        log.info("combat hud live: window {} = {} cycles; ability says {} ticks = {} cycles; "
                + "remaining samples {}", window, length, ability.cooldownTicks(),
                ability.cooldownTicks() * CYCLES_PER_TICK, remaining);
        assertTrue(remaining.getFirst() > remaining.getLast(), "remaining did not count down: "
                + remaining);
        assertTrue(Math.abs(length - ability.cooldownTicks() * CYCLES_PER_TICK) <= CYCLES_PER_TICK,
                "the window is not the ability's cooldown in client cycles: " + length);
    }

    @Test
    @Order(6)
    void dropAndTake_publishInventoryChangeEvents() {
        InventoryItem item = droppable().orElse(null);
        Assumptions.assumeTrue(item != null, "SKIPPED, not passed: nothing in the backpack offers Drop");
        events.clear();
        LocalPlayer self = api.snapshot().self();

        assertTrue(api.backpack().interactFirst(item.itemId(), DROP));
        assertTrue(waitFor(() -> inventoryEvent(item, true).isPresent(), SHORT_TIMEOUT_MS),
                "no InventoryChangeEvent for the drop of " + item);
        log.info("combat hud live: drop event {}", inventoryEvent(item, true).orElseThrow());
        // The backpack changes a tick before the ground stack exists; a Take sent in between
        // is ignored by the client (seen live), so wait for the stack first.
        assertTrue(waitFor(() -> isOnGround(item.itemId(), self), SHORT_TIMEOUT_MS),
                "the dropped item never appeared on the character's tile");
        api.queueAction(new GameAction(ActionTypes.GROUND_ITEM_OPTIONS[GROUND_TAKE_OP],
                item.itemId(), self.tileX(), self.tileY()));
        assertTrue(waitFor(() -> inventoryEvent(item, false).isPresent(), LONG_TIMEOUT_MS),
                "no InventoryChangeEvent for taking " + item + " back");
        log.info("combat hud live: take event {}", inventoryEvent(item, false).orElseThrow());
    }

    @Test
    @Order(7)
    void lodestone_roundTrip_betweenTwoUnlockedLodestones() {
        Assumptions.assumeTrue(Boolean.getBoolean(LODESTONES_PROPERTY),
                "SKIPPED, not passed: -D" + LODESTONES_PROPERTY + "=true not set");
        LodestoneNetwork network = api.lodestones();
        List<Lodestone> unlocked = network.unlocked();
        log.info("combat hud live: unlocked lodestones {}", unlocked);
        Assumptions.assumeTrue(unlocked.contains(Lodestone.LUMBRIDGE) && unlocked.size() >= 2,
                "SKIPPED, not passed: needs Lumbridge and one more lodestone unlocked");
        Lodestone away = unlocked.stream().filter(l -> l != Lodestone.LUMBRIDGE).findFirst()
                .orElseThrow();

        LodestoneNetwork.Result there = network.teleport(away);
        log.info("combat hud live: teleport to {} -> {} at {}", away, there, tile());
        LodestoneNetwork.Result back = network.teleport(Lodestone.LUMBRIDGE);
        log.info("combat hud live: teleport to LUMBRIDGE -> {} at {}", back, tile());
        log.info("combat hud live: home teleport ops after the round trip: {}",
                api.getComponentOps(1465, 34, -1));

        assertEquals(LodestoneNetwork.Result.ARRIVED, there);
        assertEquals(LodestoneNetwork.Result.ARRIVED, back);
    }

    private Optional<ActionBarSlot.AbilitySlot> targetlessAbilityWithCooldown() {
        for (ActionBarSlot slot : api.actionBar().slots()) {
            Optional<ActionBarSlot.AbilitySlot> held = switch (slot) {
                case ActionBarSlot.AbilitySlot a -> Optional.of(a);
                case ActionBarSlot.Empty e -> Optional.empty();
                case ActionBarSlot.ItemSlot i -> Optional.empty();
                case ActionBarSlot.Unresolved u -> Optional.empty();
            };
            if (held.isPresent() && held.get().ability().cooldownTicks() > 0 && isTargetless(held.get())) {
                return held;
            }
        }
        return Optional.empty();
    }

    private boolean isTargetless(ActionBarSlot.AbilitySlot held) {
        StructType struct = api.getStructType(held.ability().structId());
        return struct.intParam(PARAM_REQUIRES_TARGET).orElse(1) == 0;
    }

    private List<Integer> sampleRemaining(Ability ability) {
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            int cycle = api.snapshot().gameCycle();
            remaining.add(api.actionBar().cooldown(ability).remainingCycles(cycle));
            sleep(SAMPLE_MS);
        }
        return remaining;
    }

    private Optional<InventoryItem> droppable() {
        for (InventoryItem it : api.backpack().getItems()) {
            if (it.itemId() != LOGS) {
                continue;
            }
            if (api.getComponentOps(api.backpack().interfaceId(), api.backpack().componentId(), it.slot()).opNamed(DROP).isPresent()) {
                return Optional.of(it);
            }
        }
        return Optional.empty();
    }

    private Optional<InventoryChangeEvent> inventoryEvent(InventoryItem item, boolean isRemoval) {
        return events.stream().map(e -> switch (e) {
            case InventoryChangeEvent change -> change;
            default -> null;
        }).filter(change -> change != null && change.invId() == api.backpack().invId()
                && (isRemoval ? change.isRemoved() && change.oldItem().itemId() == item.itemId()
                        : change.isAdded() && change.newItem().itemId() == item.itemId()))
                .findFirst();
    }

    private boolean isOnGround(int itemId, LocalPlayer at) {
        return api.snapshot().groundItems().stream().anyMatch(stack -> stack.itemId() == itemId
                && stack.tileX() == at.tileX() && stack.tileY() == at.tileY());
    }

    private String orbText(int component) {
        return api.getInterfaceTree(COMBAT_BAR, component).stream()
                .map(node -> node.component().text())
                .filter(text -> text != null && text.contains("/"))
                .findFirst().orElse("<no text>");
    }

    private String tile() {
        LocalPlayer self = api.snapshot().self();
        return "(" + self.tileX() + "," + self.tileY() + "," + self.plane() + ")";
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(POLL_MS);
        }
        return condition.getAsBoolean();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
