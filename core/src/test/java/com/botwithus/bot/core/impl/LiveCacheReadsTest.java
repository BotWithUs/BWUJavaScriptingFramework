package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.combat.ActionBarSlot;
import com.botwithus.bot.api.combat.Prayer;
import com.botwithus.bot.api.diag.StubGuard;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.model.WorldMapElement;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.core.cache.NXTCache;
import com.botwithus.bot.core.gameval.HostGamevals;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.pipe.AgentSelection;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.shm.SharedRegion;
import com.botwithus.bot.core.test.LiveAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives every cache-backed read the host offers against a live client, through
 * the same open the host uses ({@link NXTCache#openForHost(long)} and
 * {@link HostGamevals#openForHost(long)}), then holds the process open.
 *
 * <p>It is the workload for checking that the host makes no network
 * connection of its own: sample this JVM's TCP connections while it runs. The
 * test itself asserts only that every read answers, which shows the local
 * cache serves all of them. Read-only; it never changes game state.</p>
 *
 * <p>Opt in with {@code -Dbotwithus.live.cacheReads=true} on top of
 * {@code :core:harnessTest}. {@code -Dbotwithus.live.cacheHoldMs} sets the
 * hold (default 8000 ms).</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.live.cacheReads", matches = "true")
class LiveCacheReadsTest {

    private static final Logger log = LoggerFactory.getLogger(LiveCacheReadsTest.class);

    private static final String HOLD_PROPERTY = "botwithus.live.cacheHoldMs";
    private static final long DEFAULT_HOLD_MS = 8_000;
    private static final int COINS = 995;
    private static final int LOGS = 1511;
    private static final int HANS = 0;
    private static final int TREE = 1276;
    /** A varbit every account has: the one the native host's check used too. */
    private static final int A_VARBIT = 1;
    private static final int A_VARP = 0;
    private static final int A_QUEST = 1;

    private SharedRegion region;
    private RpcClient rpc;
    private NXTCache cache;
    private GamevalIndex gamevals;
    private GameAPI api;

    @BeforeAll
    void connect() throws IOException {
        AgentSelection.Attach agent = LiveAgent.attachOrFail();
        log.info("cache reads live: this JVM is pid {}", ProcessHandle.current().pid());
        region = SharedRegion.open(agent.pid());
        rpc = new RpcClient(new PipeClient(agent.pipeName()));
        cache = NXTCache.openForHost(agent.pid());
        gamevals = HostGamevals.openForHost(agent.pid());
        api = new GameAPIImpl(rpc, cache, () -> new GameSnapshotImpl(region.snapshot()),
                new StubGuard(), event -> { }, gamevals);
        assertEquals(GameSnapshot.GAME_STATE_IN_GAME, api.snapshot().gameState(),
                "the character must be in the world");
    }

    @AfterAll
    void disconnect() throws Exception {
        long holdMs = Long.getLong(HOLD_PROPERTY, DEFAULT_HOLD_MS);
        log.info("cache reads live: holding {} ms", holdMs);
        Thread.sleep(holdMs);
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
    void configTypes_allAnswerFromTheCache() {
        ItemType coins = api.getItemType(COINS);
        assertNotNull(coins);
        assertEquals("Coins", coins.name());
        assertNotNull(api.getItemType(LOGS));
        assertNotNull(api.getNpcType(HANS));
        assertNotNull(api.getLocationType(TREE));
        assertNotNull(api.getQuestType(A_QUEST));
        log.info("cache reads live: item {} / npc {} / loc {} / quest {}", coins.name(),
                api.getNpcType(HANS).name(), api.getLocationType(TREE).name(),
                api.getQuestType(A_QUEST));
    }

    @Test
    void worldMapElements_answerFromTheCache() {
        List<WorldMapElement> all = api.queryWorldMapElements(Map.of());
        log.info("cache reads live: {} world map elements", all.size());
        assertFalse(all.isEmpty(), "no world map elements were read");
    }

    @Test
    void varbitsAndVarpDefaults_answer() {
        int varbit = api.getVarbit(A_VARBIT);
        log.info("cache reads live: varbit {} = {}, varp {} = {}", A_VARBIT, varbit, A_VARP,
                api.readVarp(A_VARP));
        assertTrue(varbit != GameAPI.UNRESOLVED_VARIABLE, "varbit " + A_VARBIT + " did not resolve");
    }

    @Test
    void gamevals_resolve() {
        OptionalInt logs = gamevals.id(GamevalType.ITEM, "LOGS");
        log.info("cache reads live: gameval LOGS -> {} (index available: {})", logs,
                gamevals.isAvailable());
        if (gamevals.isAvailable()) {
            assertEquals(OptionalInt.of(LOGS), logs);
        }
    }

    @Test
    void actionBarPrayersAndQuests_readTheirCacheDefinitions() {
        List<ActionBarSlot> slots = api.actionBar().slots();
        log.info("cache reads live: action bar {} slots: {}", slots.size(), slots);
        log.info("cache reads live: prayer info {}", api.prayers().info(Prayer.TIERED_DEFENCE));
        log.info("cache reads live: quest {} status {}", A_QUEST, api.quests().status(A_QUEST));
        assertFalse(slots.isEmpty(), "the action bar read nothing");
    }
}
