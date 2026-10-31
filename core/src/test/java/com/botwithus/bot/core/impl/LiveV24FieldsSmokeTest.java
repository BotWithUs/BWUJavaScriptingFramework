package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.Npc;
import com.botwithus.bot.api.entities.Player;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.PlayerAppearance;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.core.cache.NXTCache;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The wire-v24 fields read from a live agent through the same stack a script uses:
 * {@code PipeClient -> RpcClient -> GameAPIImpl} over a {@link SharedRegion} snapshot, with
 * NXTCache for NPC definitions. Read-only: nothing is clicked, typed or queued.
 *
 * <p>Logs what it saw for each field (other players' names, a morphing NPC, run energy and weight,
 * own appearance slots, and the run-orb text component's visibility), then asserts on it. Stand
 * the character in Lumbridge, where Xenia and The Sage morph, with another player in view.
 * A missing observation fails rather than skips: this class exists to certify the fields.</p>
 *
 * <p>Opt in with {@code -Dbotwithus.smoke.live=true} ({@code :core:harnessTest} sets it); pin the
 * client with {@code -Dbotwithus.harness.pid=<pid>}, and point at the cache with
 * {@code -Dnxtcache.dll=} / {@code -Dnxtcache.path=}.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
class LiveV24FieldsSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveV24FieldsSmokeTest.class);
    private static final String PID_PROPERTY = "botwithus.harness.pid";
    private static final int RUN_ORB = 1465;
    private static final int RUN_ORB_TEXT = 20;
    private static final int MAX_RUN_ENERGY = 100;

    private SharedRegion region;
    private RpcClient rpc;
    private NXTCache cache;
    private GameAPI api;

    @BeforeAll
    void connect() throws IOException {
        long pid = targetPid();
        region = SharedRegion.open(pid);
        rpc = new RpcClient(new PipeClient(PipeClient.NAME_PREFIX + pid));
        cache = NXTCache.tryOpenFromSystemProperty();
        api = new GameAPIImpl(rpc, cache, () -> new GameSnapshotImpl(region.snapshot()));
        GameSnapshot snap = api.snapshot();
        assertEquals(GameSnapshot.GAME_STATE_IN_GAME, snap.gameState(), "the character must be in the world");
        log.info("v24 live: pid={} cache={} self={}", pid, cache != null, snap.self());
    }

    @AfterAll
    void disconnect() throws Exception {
        if (rpc != null) {
            rpc.close();
        }
        if (cache != null) {
            cache.close();
        }
        if (region != null) {
            region.close();
        }
    }

    @Test
    void otherPlayersHaveNames() {
        LocalPlayer self = api.snapshot().self();
        List<Player> others = api.players().query()
                .filter(p -> p.serverIndex() != self.serverIndex()).all();
        others.forEach(p -> log.info("v24 live: player idx={} name='{}' truncated={} combat={}",
                p.serverIndex(), p.name(), p.isNameTruncated(), p.combatLevel()));
        long named = others.stream().filter(Player::hasName).count();
        log.info("v24 live: {} other players, {} named", others.size(), named);
        assertTrue(named > 0, "at least one other player must have a non-empty name");
    }

    @Test
    void morphingNpcResolvesFromItsActiveType() {
        assertNotNull(cache, "NPC names need NXTCache: set -Dnxtcache.dll / -Dnxtcache.path");
        List<Npc> morphed = api.npcs().query().filter(Npc::isMorphed).all();
        morphed.forEach(n -> log.info("v24 live: npc idx={} typeId={} activeTypeId={} name='{}' combat={} "
                        + "hidden={} options={}", n.serverIndex(), n.typeId(), n.activeTypeId(), n.name(),
                n.combatLevel(), n.isHidden(), n.getOptions()));
        long hidden = api.npcs().query().filter(Npc::isHidden).count();
        log.info("v24 live: {} morphed NPCs, {} hidden", morphed.size(), hidden);
        if (morphed.isEmpty()) {
            fail("no NPC with activeTypeId != typeId in view; stand in Lumbridge (Xenia, The Sage)");
        }
        assertTrue(morphed.stream().anyMatch(n -> n.name() != null && !n.name().isBlank()),
                "a morphing NPC must resolve a name from its active type");
    }

    @Test
    void ownRunEnergyIsInRange() {
        LocalPlayer self = api.snapshot().self();
        log.info("v24 live: runEnergy={} runWeight={} (weight unverified)", self.runEnergy(), self.runWeight());
        assertTrue(self.hasRunEnergy(), "run energy must be readable in-world on this channel");
        assertTrue(self.runEnergy() >= 0 && self.runEnergy() <= MAX_RUN_ENERGY, "0..100");
    }

    @Test
    void ownAppearanceHasSlots() {
        int own = api.snapshot().self().serverIndex();
        PlayerAppearance a = api.getPlayerAppearance(own);
        log.info("v24 live: appearance status={} render={} transform={} gender={} colours={}",
                a.status(), a.renderId(), a.npcTransformId(), a.gender(), a.colours());
        for (int i = 0; i < a.slots().size(); i++) {
            log.info("v24 live: appearance slot {} = {}", i, a.slots().get(i));
        }
        assertTrue(a.isAvailable(), "own appearance must read: " + a.status());
        assertFalse(a.slots().isEmpty());
        assertEquals(api.snapshot().self().stanceId(), a.renderId(), "render_id equals the snapshot stance");
    }

    @Test
    void runOrbTextIsVisible() {
        Component c = api.getComponent(RUN_ORB, RUN_ORB_TEXT);
        assertNotNull(c, "1465:20 must resolve with the HUD loaded");
        log.info("v24 live: 1465:20 visible={} visible_global={} hidden={} slot_flags={} parent_global={} "
                        + "text='{}'", c.isVisible(), c.isVisibleGlobal(), c.hidden(), c.visibility().slotFlags(),
                c.parentGlobalRef(), c.text());
        assertTrue(c.isVisibleGlobal(), "the always-shown run-orb text is on screen");
        assertEquals(0, c.hidden(), "v23 read hidden=1 here; v24 must read 0");
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
