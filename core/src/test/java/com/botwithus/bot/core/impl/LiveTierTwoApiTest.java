package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.api.buff.Buff;
import com.botwithus.bot.api.diag.StubGuard;
import com.botwithus.bot.api.dialog.Dialog;
import com.botwithus.bot.api.entities.Npc;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.hud.HudTab;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.api.quest.QuestStage;
import com.botwithus.bot.api.quest.QuestStatus;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.util.Timing;
import com.botwithus.bot.core.cache.NXTCache;
import com.botwithus.bot.core.gameval.SqliteGamevalIndex;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.pipe.AgentSelection;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.shm.SharedRegion;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Tier-2 API gaps against a live client:</b> the cache number decode, quest status, ribbon
 * tabs (which press a component by option name), NPC chat text, and pause/resume around a
 * running script loop. Read-only looks at buffs and the loot inventory are logged.
 *
 * <p>Opt in with {@code -Dbotwithus.live.tierTwo=true} on top of {@code :core:harnessTest}.
 * Leaving the world and coming back (exit to lobby, then log out to the login screen, each
 * followed by an unattended login) additionally needs {@code -Dbotwithus.live.leaveWorld=true}.
 * Every check logs what it saw, so the run's output is the evidence; a check whose precondition
 * the character does not meet is skipped with that reason, never passed.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
@EnabledIfSystemProperty(named = "botwithus.live.tierTwo", matches = "true")
class LiveTierTwoApiTest {

    private static final Logger log = LoggerFactory.getLogger(LiveTierTwoApiTest.class);

    private static final String LEAVE_WORLD_PROPERTY = "botwithus.live.leaveWorld";
    /** Backhand's ability struct: J2's live fixture, all-int params plus a name. */
    private static final int BACKHAND_STRUCT = 14682;
    private static final List<String> QUESTS = List.of("COOKS_ASSISTANT", "RUNE_MYSTERIES",
            "DRUIDIC_RITUAL", "THE_RESTLESS_GHOST", "DEMON_SLAYER", "BLACK_KNIGHTS_FORTRESS",
            "DRAGON_SLAYER", "SHIELD_OF_ARRAV", "VAMPYRE_SLAYER", "ERNEST_THE_CHICKEN",
            "IMP_CATCHER", "GOBLIN_DIPLOMACY", "PIRATES_TREASURE", "PRINCE_ALI_RESCUE");
    /** The varp v1 read the quest-point total from; logged, not trusted. */
    private static final int V1_QUEST_POINTS_VARP = 101;
    /** RS3 spells the option without a hyphen. */
    private static final String TALK = "Talk to";
    private static final int CHAT_RADIUS = 25;
    /** Quest ids scanned for one complete and one not-started quest. */
    private static final int MAX_QUEST_ID = 600;
    /** Ticks to let the world settle after a login before leaving it again. */
    private static final int SETTLE_TICKS = 10;
    private static final int LOGIN_SCREEN = 10;
    private static final int LOBBY = 20;
    private static final long SHORT_MS = 6_000;
    private static final long CHAT_MS = 20_000;
    private static final long LOGIN_MS = 90_000;
    private static final long PAUSE_HOLD_MS = 3_000;
    private static final int WARMUP_TICKS = 4;

    private SharedRegion region;
    private RpcClient rpc;
    private NXTCache cache;
    private GamevalIndex gamevals;
    private GameAPI api;

    @BeforeAll
    void connect() throws IOException {
        AgentSelection.Attach agent = LiveAgent.attachOrFail();
        region = SharedRegion.open(agent.pid());
        rpc = new RpcClient(new PipeClient(agent.pipeName()));
        cache = NXTCache.openForHost();
        gamevals = SqliteGamevalIndex.openDefault().<GamevalIndex>map(i -> i)
                .orElseGet(GamevalIndex::empty);
        Assumptions.assumeTrue(gamevals.isAvailable(),
                "SKIPPED, not passed: no gameval index deployed; the facades resolve by name");
        api = new GameAPIImpl(rpc, cache, () -> new GameSnapshotImpl(region.snapshot()),
                new StubGuard(), event -> { }, gamevals);
        assertEquals(GameSnapshot.GAME_STATE_IN_GAME, api.snapshot().gameState(),
                "the character must be in the world");
    }

    @AfterAll
    void disconnect() throws Exception {
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
    void structParams_decodeAsIntegerOnTheLiveCache() {
        StructType backhand = api.getStructType(BACKHAND_STRUCT);
        log.info("tier2 live: struct {} params {}", BACKHAND_STRUCT, backhand.params());
        List<Integer> numericIds = backhand.params().keySet().stream().map(Integer::parseInt)
                .filter(id -> backhand.stringParam(id).isEmpty()).toList();

        assertFalse(numericIds.isEmpty(), "the struct has numeric params");
        for (int id : numericIds) {
            Object value = backhand.params().get(Integer.toString(id));
            assertInstanceOf(Integer.class, value, "param " + id + " decoded as " + value);
        }
    }

    @Test
    @Order(2)
    void questStatus_readsCompleteAndNotStartedQuests() {
        for (String quest : QUESTS) {
            log.info("tier2 live: quest {} -> {}", quest, api.quests().status(quest));
        }
        log.info("tier2 live: varp {} (v1's quest points) = {}", V1_QUEST_POINTS_VARP,
                api.getVarp(V1_QUEST_POINTS_VARP));
        Map<QuestStage, List<QuestStatus>> byStage = new EnumMap<>(QuestStage.class);
        for (int id = 0; id <= MAX_QUEST_ID; id++) {
            QuestStatus status = api.quests().status(id);
            if (!status.name().isEmpty()) {
                byStage.computeIfAbsent(status.stage(), k -> new ArrayList<>()).add(status);
            }
        }
        byStage.forEach((stage, list) -> log.info("tier2 live: {} quests {} (first: {})", list.size(),
                stage, list.stream().limit(3).toList()));

        Assumptions.assumeTrue(byStage.containsKey(QuestStage.COMPLETE)
                        && byStage.containsKey(QuestStage.NOT_STARTED),
                "SKIPPED, not passed: the character has no complete or no not-started quest");
    }

    @Test
    @Order(3)
    void hudOpen_pressesRibbonButtonsByTheirOption() {
        List<HudTab> order = List.of(HudTab.SKILLS, HudTab.EQUIPMENT, HudTab.PRAYER,
                HudTab.MAGIC, HudTab.BACKPACK);
        int presses = 0;
        for (HudTab tab : order) {
            boolean wasOpen = api.hud().isOpen(tab);
            boolean pressed = api.hud().open(tab);
            boolean open = Timing.delayUntil(() -> api.hud().isOpen(tab), SHORT_MS);
            log.info("tier2 live: {} showing before {}, open() {}, showing after {}", tab, wasOpen,
                    pressed, open);
            presses += wasOpen ? 0 : 1;
            assertTrue(pressed, tab + ": the ribbon button offered neither Open nor Close");
            assertTrue(open, tab + " did not come to the front");
        }
        assertTrue(presses >= order.size() - 1, "most tabs should have needed a press");
    }

    @Test
    @Order(4)
    void dialogText_readsAnNpcChat() {
        LocalPlayer self = api.snapshot().self();
        Npc npc = api.npcs().query().withOption(TALK).withinDistance(CHAT_RADIUS).nearest();
        Assumptions.assumeTrue(npc != null, "SKIPPED, not passed: no NPC to talk to within "
                + CHAT_RADIUS + " tiles");
        log.info("tier2 live: talking to {}", npc);
        assertTrue(npc.interact(TALK));
        boolean chat = Timing.delayUntil(() -> Dialog.canContinue(api) || Dialog.isOpen(api), CHAT_MS);
        Optional<String> text = Dialog.getText(api);
        Optional<String> title = Dialog.getTitle(api);
        log.info("tier2 live: chat open {}, title {}, text {}, options {}", chat, title, text,
                Dialog.isOpen(api) ? Dialog.options(api) : List.of());
        walkTo(self.tileX() + 1, self.tileY());

        assertTrue(chat, "no dialog opened after Talk to");
        Assumptions.assumeTrue(Dialog.canContinue(api) || text.isPresent(),
                "SKIPPED, not passed: the NPC opened with options, not a chat page");
        assertTrue(text.isPresent() && !text.get().isBlank(), "chat page with no text");
    }

    @Test
    @Order(5)
    void pauseResume_holdsARunningLoop() throws InterruptedException {
        AtomicInteger loops = new AtomicInteger();
        ScriptRuntime runtime = new ScriptRuntime(new ScriptContextImpl(api, new EventBusImpl(),
                new MessageBusImpl()));
        ScriptRunner runner = runtime.registerScript(new TickCounter(loops));
        runner.start();
        try {
            assertTrue(Timing.delayUntil(() -> loops.get() >= 2, SHORT_MS * 2));
            runner.pause();
            Thread.sleep(Timing.TICK_MS * 2L);
            int atPause = loops.get();
            int tickAtPause = api.snapshot().serverTick();
            Thread.sleep(PAUSE_HOLD_MS);
            int ticksPassed = api.snapshot().serverTick() - tickAtPause;
            log.info("tier2 live: paused at {} loops; {} server ticks later still {}", atPause,
                    ticksPassed, loops.get());
            assertEquals(atPause, loops.get(), "a loop ran while paused");
            assertTrue(ticksPassed >= WARMUP_TICKS, "the server tick did not advance");

            runner.resume();
            assertTrue(Timing.delayUntil(() -> loops.get() >= atPause + 2, SHORT_MS * 2),
                    "no loop after resume");
            log.info("tier2 live: resumed, {} loops", loops.get());
        } finally {
            runner.stop();
            runner.awaitStop(SHORT_MS);
        }
    }

    @Test
    @Order(6)
    void buffsAndLoot_readWithoutError() {
        for (Buff buff : Buff.values()) {
            log.info("tier2 live: {}", api.buffs().state(buff));
        }
        log.info("tier2 live: loot window open {}, area loot {}, contents {}",
                api.loot().isOpen(), api.loot().isAreaLootEnabled(), api.loot().contents());
    }

    @Test
    @Order(7)
    @EnabledIfSystemProperty(named = LEAVE_WORLD_PROPERTY, matches = "true")
    void exitToLobby_thenLogBackIn() {
        assertTrue(api.hud().exitToLobby(), "exit_to_lobby refused");
        assertTrue(awaitState(LOBBY), "never reached the lobby");
        log.info("tier2 live: in the lobby; logging back in");
        rpc.callSync("login_to_game", Map.of());
        assertTrue(awaitState(GameSnapshot.GAME_STATE_IN_GAME), "did not get back into the world");
    }

    @Test
    @Order(8)
    @EnabledIfSystemProperty(named = LEAVE_WORLD_PROPERTY, matches = "true")
    void logout_thenLogBackIn() {
        Timing.delayTicks(api, SETTLE_TICKS, SETTLE_TICKS);
        assertTrue(api.hud().logout(), "logout refused");
        assertTrue(awaitState(LOGIN_SCREEN), "never reached the login screen");
        log.info("tier2 live: at the login screen; logging back in");
        rpc.callSync("login_to_lobby", Map.of());
        assertTrue(awaitState(LOBBY), "did not reach the lobby");
        rpc.callSync("login_to_game", Map.of());
        assertTrue(awaitState(GameSnapshot.GAME_STATE_IN_GAME), "did not get back into the world");
    }

    private boolean awaitState(int state) {
        return Timing.delayUntil(() -> api.snapshot().gameState() == state, LOGIN_MS);
    }

    private void walkTo(int x, int y) {
        api.queueAction(new GameAction(ActionTypes.WALK, 1, x, y));
        Timing.delayUntil(() -> !Dialog.canContinue(api) && !Dialog.isOpen(api), SHORT_MS);
    }

    private static boolean has(List<QuestStatus> statuses, QuestStage stage) {
        return statuses.stream().anyMatch(s -> s.stage() == stage);
    }

    /** Counts loops, each of which waits one server tick through {@link Timing}. */
    @ScriptManifest(name = "TierTwoTickCounter", version = "1.0", author = "test")
    private final class TickCounter implements BotScript {
        private final AtomicInteger loops;

        TickCounter(AtomicInteger loops) {
            this.loops = loops;
        }

        @Override
        public void onStart(ScriptContext ctx) {
        }

        @Override
        public int onLoop() {
            Timing.delayTicks(api, 1, 1);
            loops.incrementAndGet();
            return 1;
        }

        @Override
        public void onStop() {
        }
    }
}
