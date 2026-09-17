package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.VarbitType;
import com.botwithus.bot.api.model.VarbitValue;
import com.botwithus.bot.core.cache.NXTCache;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live smoke test for the {@link com.botwithus.bot.api.domain.VariableAPI} reads
 * ({@code getVarp} / {@code getVarbit} / {@code getVarcInt} / {@code getVarcString}
 * / {@code queryVarbits}) end-to-end against a running, injected client. Stands up
 * the real {@code PipeClient → RpcClient → GameAPIImpl} stack — the same path a
 * BotScript takes — so it exercises the producer var-read RPCs and the
 * consumer-side varbit decode together.
 *
 * <p>Disabled by default — opt in with {@code -Dbotwithus.smoke.live=true} (the
 * {@code :core:liveSmokeTest} Gradle task sets it). Requires the rebuilt
 * NXTLibrary DLL injected into a client that is at least at the lobby (vars only
 * populate from game-state 20 up); the class self-skips otherwise.</p>
 *
 * <p>The varbit checks additionally need the cache for the varbit type configs —
 * rerun with {@code -Dnxtcache.dll=<NXTCache.dll>} plus either
 * {@code -Dnxtcache.live=true} or {@code -Dnxtcache.path=<cache dir>}. Without
 * those, the varbit tests self-skip while the varp/varc tests still run.</p>
 *
 * <p>Read-only — never mutates game state.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
class LiveVariableApiSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveVariableApiSmokeTest.class);

    private static final int LOBBY = 20;
    private static final int VARP_SCAN = 4000;
    private static final int VARC_SCAN = 4000;
    private static final int VARBIT_SCAN = 6000;

    private RpcClient rpc;
    private NXTCache cache;   // null when not configured — varbit tests then self-skip
    private GameAPI api;

    @BeforeAll
    void connect() {
        List<String> pipes = PipeClient.scanPipes(PipeClient.NAME_PREFIX);
        Assumptions.assumeFalse(pipes.isEmpty(),
                "no BotWithUs_<pid> pipe visible — inject the DLL into a running client");

        PipeClient pipe = new PipeClient(pipes.getFirst());
        rpc = new RpcClient(pipe);

        // The cache is only needed for varbit decode (base-var + bit range lookup).
        // Tolerate it being unconfigured / unavailable so the varp/varc reads still run.
        try {
            cache = NXTCache.tryOpenFromSystemProperty();
        } catch (Throwable t) {
            log.warn("NXTCache unavailable ({}): varbit tests will skip. "
                    + "Set -Dnxtcache.dll=<NXTCache.dll> and -Dnxtcache.live=true (or -Dnxtcache.path=<dir>).",
                    t.toString());
            cache = null;
        }

        api = new GameAPIImpl(rpc, cache);

        int state = api.getLoginState().state();
        Assumptions.assumeTrue(state >= LOBBY,
                "vars only populate from game-state " + LOBBY + " (lobby) up; current state=" + state);
        log.info("connected to {}; cache={}, loginState={}", pipe.getPipePath(), cache != null, state);
    }

    @AfterAll
    void disconnect() {
        if (rpc != null) {
            rpc.close();
        }
        if (cache != null) {
            try {
                cache.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    @Test
    void varpReadsSurfaceLiveData() {
        int populated = 0;
        int lastId = -1;
        int lastVal = 0;
        for (int id = 0; id < VARP_SCAN; id++) {
            int v = api.getVarp(id);
            if (v != -1) {
                populated++;
                lastId = id;
                lastVal = v;
            }
        }
        assertTrue(populated > 0,
                "expected some non-(-1) varps in 0.." + VARP_SCAN + " — is the client in lobby/in-game?");
        log.info("varp scan: {} populated; e.g. varp {} = {}", populated, lastId, lastVal);
    }

    @Test
    void varbitDecodeMatchesBaseVarpShiftMask() {
        Assumptions.assumeTrue(cache != null,
                "varbit decode needs the cache — rerun with -Dnxtcache.dll=<...> and -Dnxtcache.live=true");

        int verified = 0;
        int defsSeen = 0;
        for (int vb = 0; vb < VARBIT_SCAN && verified < 10; vb++) {
            VarbitType def = cache.getVarbit(vb);
            if (def == null) {
                continue;
            }
            defsSeen++;
            if (def.domainType() != 0) {
                continue;   // only player-domain (varp-backed) varbits are decoded here
            }
            int width = def.msb() - def.lsb() + 1;
            if (width <= 0 || width > 32) {
                continue;
            }
            // Read the base varp twice around the varbit read; if it changed mid-window
            // (a ticking counter) skip rather than compare against a stale base.
            int base1 = api.getVarp(def.varId());
            int actual = api.getVarbit(vb);
            int base2 = api.getVarp(def.varId());
            if (base1 != base2) {
                continue;
            }
            int mask = width == 32 ? -1 : (1 << width) - 1;
            int expected = (base1 >>> def.lsb()) & mask;
            assertEquals(expected, actual,
                    "varbit " + vb + " (varp " + def.varId() + " bits " + def.lsb() + ".." + def.msb() + ")");
            verified++;
        }
        Assumptions.assumeTrue(verified > 0,
                "no decodable player-domain varbits found (saw " + defsSeen + " defs)");
        log.info("verified {} varbit decodes against their base varp", verified);
    }

    @Test
    void queryVarbitsMatchesIndividualReads() {
        Assumptions.assumeTrue(cache != null, "queryVarbits test needs the cache (varbit defs)");

        List<Integer> ids = new ArrayList<>();
        for (int vb = 0; vb < VARBIT_SCAN && ids.size() < 8; vb++) {
            if (cache.getVarbit(vb) != null) {
                ids.add(vb);
            }
        }
        Assumptions.assumeFalse(ids.isEmpty(), "no varbit defs found in cache");

        List<VarbitValue> batch = api.queryVarbits(ids);
        assertEquals(ids.size(), batch.size(), "queryVarbits returns one entry per input id");
        for (int i = 0; i < ids.size(); i++) {
            assertEquals(ids.get(i).intValue(), batch.get(i).varbitId(), "ids are preserved in order");
            assertEquals(api.getVarbit(ids.get(i)), batch.get(i).value(),
                    "batch value matches individual getVarbit for varbit " + ids.get(i));
        }
        log.info("queryVarbits batch of {} matches individual reads", ids.size());
    }

    @Test
    void varcIntReadsSurfaceLiveData() {
        int populated = 0;
        int lastId = -1;
        int lastVal = 0;
        for (int id = 0; id < VARC_SCAN; id++) {
            int v = api.getVarcInt(id);
            if (v != -1) {
                populated++;
                lastId = id;
                lastVal = v;
            }
        }
        assertTrue(populated > 0, "expected some non-(-1) client vars (varc) in 0.." + VARC_SCAN);
        log.info("varc-int scan: {} populated; e.g. varc {} = {}", populated, lastId, lastVal);
    }

    // jag::String stores up to ~22 chars inline (SSO); longer values live on the
    // heap, where the node's payload slot holds a char* rather than the bytes.
    // The int/string byte-agreement cross-check below therefore only holds for
    // short (inline) strings — cap the candidates well under the SSO boundary.
    private static final int SSO_INLINE_MAX = 15;

    @Test
    void varcStringDecodesAndShortStringsAgreeWithIntBytes() {
        // A short (inline-SSO) string varc and an int varc share the node's
        // fixed_any payload slot, so get_varc_int's low 4 bytes must equal the
        // string's first 4 chars (little-endian). This jointly proves both reads
        // surface the same raw payload. (Long/heap strings are read fine too —
        // see the value logged — but their payload slot is a pointer, so the
        // byte-agreement doesn't apply and they're skipped here.)
        int verified = 0;
        String sample = null;
        for (int id = 0; id < VARC_SCAN && verified < 3; id++) {
            String s = api.getVarcString(id);
            if (s == null || s.length() < 4 || s.length() > SSO_INLINE_MAX) {
                continue;
            }
            boolean ascii = s.chars().allMatch(c -> c < 128);
            boolean hasLetter = s.chars().anyMatch(Character::isLetter);
            if (!ascii || !hasLetter) {
                continue;
            }
            byte[] b = s.getBytes(StandardCharsets.US_ASCII);
            int expectedLow = (b[0] & 0xFF)
                    | (b[1] & 0xFF) << 8
                    | (b[2] & 0xFF) << 16
                    | (b[3] & 0xFF) << 24;
            assertEquals(expectedLow, api.getVarcInt(id),
                    "varc " + id + " '" + s + "': get_varc_int low bytes must equal the string's "
                            + "first 4 chars (shared inline fixed_any payload)");
            sample = s;
            verified++;
        }
        Assumptions.assumeTrue(verified > 0,
                "no short (<=" + SSO_INLINE_MAX + "-char) ASCII string varcs populated to cross-check");
        log.info("verified {} short string varc(s); e.g. '{}'", verified, sample);
    }
}
