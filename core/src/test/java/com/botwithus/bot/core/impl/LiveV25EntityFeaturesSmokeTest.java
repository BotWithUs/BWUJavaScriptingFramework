package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.Npc;
import com.botwithus.bot.api.entities.Player;
import com.botwithus.bot.api.model.EntityKind;
import com.botwithus.bot.api.model.EntityRead;
import com.botwithus.bot.api.model.EntityReadStatus;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.Headbar;
import com.botwithus.bot.api.model.HintArrow;
import com.botwithus.bot.api.model.OverheadText;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.shm.SharedRegion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The wire-v25 features read from a live agent through the stack a script uses. Logs each value it
 * saw, then asserts on it. A missing observation fails rather than skips, so the hint-arrow read
 * needs a character holding an arrow; the harness character holds one in its owner tree.
 *
 * <p>Opt in with {@code -Dbotwithus.smoke.live=true}, pin the client with
 * {@code -Dbotwithus.harness.pid=<pid>}. The hint-arrow and silent-self reads are read-only. <b>The
 * other four move the character</b>, so they also need {@code -Dbotwithus.live.walks=true}: a walk,
 * a trip to the Rimmington chicks and back, an attack on the ducks by the Lumbridge pond, and a wait
 * at the bank chest for another player to come into view. They
 * start from the Lumbridge bank chest (3215,3256), like the agent's own scenarios.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
class LiveV25EntityFeaturesSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveV25EntityFeaturesSmokeTest.class);
    private static final String PID_PROPERTY = "botwithus.harness.pid";
    private static final String WALKS_PROPERTY = "botwithus.live.walks";

    private static final int WALK_ACTION = 23;
    private static final int NPC_OPTION_2_ACTION = 10;
    private static final int[][] TO_CHICKS = {{3170, 3250}, {3130, 3265}, {3090, 3274}, {3033, 3281}};
    private static final int[][] FROM_CHICKS = {{3090, 3274}, {3130, 3265}, {3170, 3250}, {3215, 3256}};
    private static final int[] WALK_START = {3222, 3235};
    private static final int[] WALK_END = {3235, 3235};
    private static final int[] BANK_CHEST = {3215, 3256};
    /** Further than this between a walking player's two tiles means one of them is misread. */
    private static final int MAX_SERVER_TILE_LEAD = 16;
    private static final Duration OTHER_PLAYER_TIMEOUT = Duration.ofSeconds(180);
    private static final int CHICK = 30870;
    private static final int[] DUCKS = {46, 2693};
    private static final int MIN_RUNNING_LEAD_TILES = 2;

    private static final Duration LEG_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration CHICK_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration BAR_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration LEAD_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(5);
    private static final long ATTACK_PERIOD_MS = 8000;
    private static final long POLL_MS = 50;
    private static final long MIN_TEXT_MS_LEFT = 500;

    private SharedRegion region;
    private RpcClient rpc;
    private GameAPI api;

    @BeforeAll
    void connect() throws IOException {
        long pid = targetPid();
        region = SharedRegion.open(pid);
        rpc = new RpcClient(new PipeClient(PipeClient.NAME_PREFIX + pid));
        api = new GameAPIImpl(rpc, null, () -> new GameSnapshotImpl(region.snapshot()));
        assertEquals(GameSnapshot.GAME_STATE_IN_GAME, api.snapshot().gameState(), "must be in the world");
    }

    @AfterAll
    void disconnect() throws Exception {
        if (rpc != null) {
            rpc.close();
        }
        if (region != null) {
            region.close();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = WALKS_PROPERTY, matches = "true")
    void serverTileLeadsTheVisualTileDuringAWalk() throws InterruptedException {
        walkTo(WALK_START);
        awaitServerTileSettled();
        LocalPlayer idle = self();
        log.info("v25 live: idle visual=({},{},{}) server=({},{},{})", idle.tileX(), idle.tileY(), idle.plane(),
                idle.serverTileX(), idle.serverTileY(), idle.serverPlane());
        assertTrue(idle.hasServerTile());
        assertEquals(idle.tileX(), idle.serverTileX());
        assertEquals(idle.tileY(), idle.serverTileY());
        assertEquals(idle.plane(), idle.serverPlane());

        queueWalk(WALK_END);
        int maxLead = 0;
        long deadline = System.nanoTime() + LEAD_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            LocalPlayer s = self();
            int lead = s.serverTileX() - s.tileX();
            if (lead > maxLead) {
                maxLead = lead;
                log.info("v25 live: walk visual=({},{}) server=({},{}) leadX={}", s.tileX(), s.tileY(),
                        s.serverTileX(), s.serverTileY(), lead);
            }
            Thread.sleep(POLL_MS);
        }
        log.info("v25 live: largest server tile lead on the walk = {}", maxLead);
        assertTrue(maxLead >= MIN_RUNNING_LEAD_TILES, "server tile must lead a running walk, saw " + maxLead);

        awaitTile(WALK_END, LEG_TIMEOUT);
        awaitServerTileSettled();
        LocalPlayer arrived = self();
        log.info("v25 live: arrived visual=({},{}) server=({},{})", arrived.tileX(), arrived.tileY(),
                arrived.serverTileX(), arrived.serverTileY());
        assertEquals(WALK_END[0], arrived.serverTileX());
        assertEquals(WALK_END[1], arrived.serverTileY());

        int own = arrived.serverIndex();
        Player row = ownPlayer();
        log.info("v25 live: own players[] row server=({},{},{}) hasServerTile={}", row.serverTileX(),
                row.serverTileY(), row.serverPlane(), row.hasServerTile());
        assertTrue(row.hasServerTile());
        assertEquals(arrived.serverTileX(), row.serverTileX(), "the own row agrees with self");
        assertEquals(arrived.serverTileY(), row.serverTileY(), "the own row agrees with self");
        assertEquals(arrived.serverPlane(), row.serverPlane(), "the own row agrees with self");
        walkTo(BANK_CHEST);
    }

    @Test
    void hintArrowsList() {
        EntityRead<List<HintArrow>> read = api.queryHintArrows();
        log.info("v25 live: hint arrows status={} truncated={} count={}", read.status(), read.isTruncated(),
                read.value().size());
        read.value().forEach(a -> log.info("v25 live: hint arrow {}", a));
        assertTrue(read.isAvailable(), "query_hint_arrows must answer in the world: " + read.status());
        assertFalse(read.value().isEmpty(), "the character must hold an arrow, or this reads nothing");
        log.info("v25 live: facade nearest={} count={}", api.hintArrows().nearest(), api.hintArrows().count());
        assertEquals(read.value().size(), api.hintArrows().count(), "the facade sees what the RPC returned");
    }

    @Test
    void silentSelfHasNoOverheadText() {
        int own = self().serverIndex();
        EntityRead<Optional<OverheadText>> read = api.getOverheadText(EntityKind.PLAYER, own);
        log.info("v25 live: own overhead status={} value={}", read.status(), read.value());
        assertTrue(read.isAvailable(), "own player must answer: " + read.status());
        assertTrue(read.value().isEmpty(), "this smoke sends no chat, so self is silent");
        assertTrue(ownPlayer().getOverheadText().isEmpty(), "the Player facade agrees");
        assertEquals(EntityReadStatus.NOT_FOUND, api.getOverheadText(EntityKind.PLAYER, Short.MAX_VALUE).status(),
                "an index out of range refuses with its own code");
    }

    @Test
    @EnabledIfSystemProperty(named = WALKS_PROPERTY, matches = "true")
    void chicksAtRimmingtonSpeak() throws InterruptedException {
        for (int[] leg : TO_CHICKS) {
            walkTo(leg);
        }
        try {
            long deadline = System.nanoTime() + CHICK_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                for (Npc chick : api.npcs().query().filter(n -> n.typeId() == CHICK).all()) {
                    EntityRead<Optional<OverheadText>> read = api.getOverheadText(EntityKind.NPC, chick.serverIndex());
                    if (read.value().filter(t -> t.expiresInMs() >= MIN_TEXT_MS_LEFT).isPresent()) {
                        Optional<String> facade = chick.getOverheadText();
                        log.info("v25 live: chick idx={} overhead={} facade={}", chick.serverIndex(),
                                read.value().get(), facade);
                        assertEquals("Cheep!", read.value().get().text());
                        assertEquals(Optional.of("Cheep!"), facade, "the Npc facade asks the same table");
                        return;
                    }
                }
                Thread.sleep(POLL_MS);
            }
            fail("no chick spoke in " + CHICK_TIMEOUT);
        } finally {
            for (int[] leg : FROM_CHICKS) {
                walkTo(leg);
            }
        }
    }

    /** A row past the first: pins the 56-byte stride, and the fields, on data the agent wrote. */
    @Test
    @EnabledIfSystemProperty(named = WALKS_PROPERTY, matches = "true")
    void anotherPlayersRowCarriesAServerTile() throws InterruptedException {
        walkTo(BANK_CHEST);
        int own = self().serverIndex();
        long deadline = System.nanoTime() + OTHER_PLAYER_TIMEOUT.toNanos();
        int maxCount = 0;
        while (System.nanoTime() < deadline) {
            maxCount = Math.max(maxCount, api.snapshot().players().count());
            List<Player> others = api.players().query().filter(p -> p.serverIndex() != own).all();
            if (!others.isEmpty()) {
                log.info("v25 live: players[] count={} others={}", api.snapshot().players().count(), others.size());
                others.forEach(this::assertPlausibleServerTile);
                return;
            }
            Thread.sleep(POLL_MS);
        }
        fail("no other player came into view in " + OTHER_PLAYER_TIMEOUT + "; largest players[] count " + maxCount);
    }

    private void assertPlausibleServerTile(Player p) {
        log.info("v25 live: other player idx={} name='{}' visual=({},{},{}) server=({},{},{})", p.serverIndex(),
                p.name(), p.tileX(), p.tileY(), p.plane(), p.serverTileX(), p.serverTileY(), p.serverPlane());
        assertTrue(p.hasServerTile(), "a loaded player has a server tile on the live channel");
        assertTrue(p.distanceTo(p.serverTileX(), p.serverTileY()) <= MAX_SERVER_TILE_LEAD,
                "the server tile stays near the visual tile");
    }

    /** Attacks ducks until the bars show: the same fight the agent's get-headbars scenario uses. */
    @Test
    @EnabledIfSystemProperty(named = WALKS_PROPERTY, matches = "true")
    void headbarsFromAFight() throws InterruptedException {
        walkTo(WALK_END);
        try {
            readBarsDuringAFight();
        } finally {
            walkTo(BANK_CHEST);
        }
    }

    private void readBarsDuringAFight() throws InterruptedException {
        boolean sawOwnBar = false;
        boolean sawNpcBar = false;
        long lastAttack = 0;
        long deadline = System.nanoTime() + BAR_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline && !(sawOwnBar && sawNpcBar)) {
            List<Npc> ducks = api.npcs().query().filter(n -> isDuck(n.typeId())).all();
            if (System.currentTimeMillis() - lastAttack > ATTACK_PERIOD_MS && !ducks.isEmpty()) {
                api.queueAction(new GameAction(NPC_OPTION_2_ACTION, ducks.getFirst().serverIndex(), 0, 0));
                lastAttack = System.currentTimeMillis();
            }
            List<Headbar> ownBars = ownPlayer().getHeadbars();
            if (!ownBars.isEmpty() && !sawOwnBar) {
                sawOwnBar = true;
                ownBars.forEach(b -> log.info("v25 live: own headbar {} percent={}", b, b.percent()));
            }
            for (Npc n : api.npcs().query().all()) {
                List<Headbar> bars = n.getHeadbars();
                if (!bars.isEmpty() && !sawNpcBar) {
                    sawNpcBar = true;
                    bars.forEach(b -> log.info("v25 live: npc idx={} type={} headbar {} percent={}",
                            n.serverIndex(), n.typeId(), b, b.percent()));
                }
            }
            Thread.sleep(POLL_MS);
        }
        log.info("v25 live: saw own bar={} npc bar={}", sawOwnBar, sawNpcBar);
        assertTrue(sawOwnBar, "no headbar on self while attacking");
        assertTrue(sawNpcBar, "no headbar on any NPC during the fight");
    }

    private static boolean isDuck(int typeId) {
        return typeId == DUCKS[0] || typeId == DUCKS[1];
    }

    private Player ownPlayer() {
        Player own = api.players().byServerIndex(self().serverIndex());
        assertNotNull(own, "the local player has a row in players[]");
        return own;
    }

    private LocalPlayer self() {
        return api.snapshot().self();
    }

    private void queueWalk(int[] tile) {
        api.queueAction(new GameAction(WALK_ACTION, 1, tile[0], tile[1]));
    }

    private void walkTo(int[] tile) throws InterruptedException {
        queueWalk(tile);
        awaitTile(tile, LEG_TIMEOUT);
    }

    private void awaitTile(int[] tile, Duration timeout) throws InterruptedException {
        await(() -> self().tileX() == tile[0] && self().tileY() == tile[1], timeout,
                "reach " + tile[0] + "," + tile[1]);
    }

    private void awaitServerTileSettled() throws InterruptedException {
        await(() -> self().serverTileX() == self().tileX() && self().serverTileY() == self().tileY(),
                SETTLE_TIMEOUT, "server tile settle on the visual tile");
    }

    private static void await(BooleanSupplier condition, Duration timeout, String what) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(POLL_MS);
        }
        fail("did not " + what + " within " + timeout);
    }

    private static long targetPid() {
        String pid = System.getProperty(PID_PROPERTY);
        if (pid != null && !pid.isBlank()) {
            return Long.parseLong(pid.trim());
        }
        List<Long> pids = SharedRegion.discoverPids();
        if (pids.isEmpty()) {
            fail("No BotWithUs_<pid> pipe visible; inject the agent into a running game first");
        }
        return pids.getFirst();
    }
}
