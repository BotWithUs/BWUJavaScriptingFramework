package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.VarKind;
import com.botwithus.bot.api.model.VarbitRead;
import com.botwithus.bot.api.model.VarbitType;
import com.botwithus.bot.api.model.VarpRead;
import com.botwithus.bot.api.model.VarpState;
import com.botwithus.bot.core.cache.NXTCache;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The varp state API end to end: the real {@code PipeClient -> RpcClient -> GameAPIImpl}
 * stack against an injected agent, plus the real NXTCache for defaults. One varp is checked
 * in each state, plus a LONG varp and a varbit over a defaulted base.
 *
 * <p>Opt in with {@code -Dbotwithus.smoke.live=true}. Pin the client with
 * {@code -Dbotwithus.harness.pid=<pid>} when more than one is running. The defaults need a
 * varp-capable NXTCache.dll ({@code -Dnxtcache.dll=...}) and a local game cache.</p>
 *
 * <p>The in-world cases need the character in the world; the lobby case needs it in the
 * lobby, and logs it out itself only with the second opt-in
 * {@code -Dbotwithus.varp.lobby=true}. Everything else is read-only.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
class LiveVarpStateTest {

    private static final Logger log = LoggerFactory.getLogger(LiveVarpStateTest.class);

    private static final int IN_GAME = 30;
    private static final int LOBBY = 20;
    /** Life points: always set on a character in the world. */
    private static final int HEALTH_VARP = 13537;
    /** A BOOLEAN varp without opcode 7 (default -1), unset on the character this was run on. */
    private static final int BOOLEAN_DOMAIN_VARP = 193;
    /** A LONG varp (CONSTRUCTION_HOUSE_HOST_HASH64). */
    private static final int LONG_VARP = 12921;
    private static final int NOT_A_VARP = 20000;
    private static final int NEGATIVE_ID = -5;
    private static final int VARBIT_SCAN = 60_000;
    private static final long WAIT_SECONDS = 60;
    private static final long POLL_MILLIS = 50;

    private RpcClient rpc;
    private NXTCache cache;
    private GameAPIImpl api;

    @BeforeAll
    void connect() throws IOException, InterruptedException {
        List<String> pipes = PipeClient.scanPipes(PipeClient.NAME_PREFIX);
        String pid = System.getProperty("botwithus.harness.pid");
        List<String> chosen = pid == null ? pipes
                : pipes.stream().filter(p -> p.endsWith("_" + pid)).toList();
        Assumptions.assumeFalse(chosen.isEmpty(), "no agent pipe for pid " + pid);
        rpc = new RpcClient(new PipeClient(chosen.getFirst()));
        Assumptions.assumeTrue(NXTCache.supportsVarpInfo(), "NXTCache.dll lacks nxt_get_varp_info");
        cache = NXTCache.openForHost();
        awaitUntil(cache::isVarpInfoWarm, "the varp warm-up never finished");
        api = new GameAPIImpl(rpc, cache);
        log.info("connected to {}, login state {}", chosen.getFirst(), api.getLoginState().state());
    }

    private void assumeInWorld() {
        Assumptions.assumeTrue(api.getLoginState().state() == IN_GAME, "the character is not in world");
    }

    @AfterAll
    void disconnect() {
        if (rpc != null) {
            rpc.close();
        }
        if (cache != null) {
            cache.close();
        }
    }

    @Test
    @Order(1)
    void aSetVarp_isSet_withItsValue() {
        assumeInWorld();
        VarpRead read = api.readVarp(HEALTH_VARP);
        log.info("health: {}", read);
        assertEquals(VarpState.SET, read.state());
        assertEquals(VarKind.INT, read.kind());
        assertTrue(read.value() > 0, "a character in the world has life points");
        assertEquals(read.value(), api.getVarp(HEALTH_VARP));
    }

    @Test
    @Order(2)
    void anUnsetVarp_readsTheCacheDefault_notTheAgentsZero() {
        assumeInWorld();
        VarpRead read = api.readVarp(BOOLEAN_DOMAIN_VARP);
        log.info("boolean-domain varp: {}", read);
        Assumptions.assumeTrue(read.state() == VarpState.DEFAULT_NOT_SET_CLIENTSIDE,
                "varp " + BOOLEAN_DOMAIN_VARP + " is set on this character");
        assertTrue(read.defaultVerified());
        assertEquals(-1, read.value(), "BOOLEAN without opcode 7 defaults to -1, as the game reads");
    }

    @Test
    @Order(3)
    void anIdThatIsNoVarp_isNoSuchVarp() {
        assumeInWorld();
        VarpRead read = api.readVarp(NOT_A_VARP);
        log.info("not a varp: {}", read);
        assertEquals(VarpState.NO_SUCH_VARP, read.state());
        assertEquals(VarpRead.NO_VALUE, read.value());
    }

    @Test
    @Order(4)
    void aReadTheAgentCannotMake_isUnavailable() {
        assumeInWorld();
        VarpRead read = api.readVarp(NEGATIVE_ID);
        log.info("negative id: {}", read);
        assertEquals(VarpState.UNAVAILABLE, read.state());
    }

    @Test
    @Order(5)
    void aLongVarp_reportsKindLong_andAFullWidthValue() {
        assumeInWorld();
        VarpRead read = api.readVarp(LONG_VARP);
        log.info("long varp: {}", read);
        Assumptions.assumeTrue(read.isSet(), "varp " + LONG_VARP + " is not set on this character");
        assertEquals(VarKind.LONG, read.kind());
        assertEquals(read.value64(), api.getVarpLong(LONG_VARP));
        assertEquals((int) read.value64(), read.value(), "the int form is the low 32 bits");
    }

    /**
     * A varbit whose base varp is unset with a default of -1 decodes all ones, as the game
     * reads it. Any such base will do (several varp types default to -1), so the case finds
     * one from the cache's varbits and a batched read of their bases.
     */
    @Test
    @Order(6)
    void aVarbitOverADefaultedMinusOneBase_readsAllOnes() {
        assumeInWorld();
        Map<Integer, VarbitType> firstVarbitOnBase = new LinkedHashMap<>();
        for (int id = 0; id < VARBIT_SCAN; id++) {
            VarbitType t = cache.getVarbit(id);
            if (t != null && t.domainType() == 0) {
                firstVarbitOnBase.putIfAbsent(t.varId(), t);
            }
        }
        VarpRead base = api.readVarps(List.copyOf(firstVarbitOnBase.keySet())).stream()
                .filter(r -> r.state() == VarpState.DEFAULT_NOT_SET_CLIENTSIDE)
                .filter(r -> r.defaultVerified() && r.value() == -1)
                .findFirst().orElse(null);
        Assumptions.assumeTrue(base != null, "no varbit over an unset base whose default is -1");
        VarbitType def = firstVarbitOnBase.get(base.id());
        VarbitRead read = api.readVarbit(def.id());
        log.info("varbit {} over unset varp {} (default -1) bits {}..{}: {}",
                def.id(), def.varId(), def.lsb(), def.msb(), read);
        int width = def.msb() - def.lsb() + 1;
        int allOnes = width == Integer.SIZE ? -1 : (1 << width) - 1;
        assertEquals(new VarbitRead(def.id(), VarpState.DEFAULT_NOT_SET_CLIENTSIDE, allOnes, true), read);
    }

    /**
     * In the lobby every varp read is UNAVAILABLE. Asserted directly when the client is
     * already in the lobby; from the world it first logs the character out, which MUTATES
     * the client and so needs {@code -Dbotwithus.varp.lobby=true}.
     */
    @Test
    @Order(7)
    void inTheLobby_aRealVarpIsUnavailable() throws InterruptedException {
        if (api.getLoginState().state() == IN_GAME) {
            Assumptions.assumeTrue(Boolean.getBoolean("botwithus.varp.lobby"),
                    "in world; -Dbotwithus.varp.lobby=true lets this log out to the lobby");
            rpc.callSync("exit_to_lobby", Map.of());
            awaitUntil(() -> api.getLoginState().state() == LOBBY, "never reached the lobby");
        }
        Assumptions.assumeTrue(api.getLoginState().state() == LOBBY, "not in the lobby");

        VarpRead read = api.readVarp(HEALTH_VARP);
        log.info("health in the lobby: {}", read);
        assertEquals(VarpState.UNAVAILABLE, read.state());
        assertEquals(VarpRead.NO_VALUE, api.getVarp(HEALTH_VARP));
    }

    private static void awaitUntil(Condition condition, String failure) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!condition.holds()) {
            assertTrue(System.nanoTime() < deadline, failure);
            Thread.sleep(POLL_MILLIS);
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean holds();
    }
}
