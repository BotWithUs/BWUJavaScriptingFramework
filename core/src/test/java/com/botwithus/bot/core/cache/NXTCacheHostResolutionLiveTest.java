package com.botwithus.bot.core.cache;

import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.core.impl.GameAPIImpl;
import com.botwithus.bot.core.util.NativeCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end check that a host with no {@code -D} flag set opens a usable cache
 * and answers a real config-type lookup — the defect this class exists for was
 * a shipped install throwing {@code IllegalStateException} on every one.
 *
 * <p>Loads {@code NXTCache.dll} through Panama, so it needs
 * {@code --enable-native-access} (the {@code :core:test} task passes it) and a
 * deployed DLL. It skips when no DLL is present, which is what lets it live in
 * the CI-run {@code test} task; a skip here is not a pass, so when this is the
 * evidence for a change, watch the case report as {@code passed}.</p>
 *
 * <p>Exactly one of the two resolution cases runs per JVM, selected by whether
 * {@code -Dnxtcache.path} is set. Both matter, so both are exercised by running
 * the task twice — see {@code :core:nxtCacheResolutionTest}.</p>
 */
class NXTCacheHostResolutionLiveTest {

    /** Coins. Chosen because it is present in every cache and its name never changes. */
    private static final int COINS_ITEM_ID = 995;

    /** Opt-in for the one case that reaches the network. */
    private static final String LIVE_FALLBACK_OPT_IN = "nxtcache.smoke.live";

    @BeforeAll
    static void requireTheDecoderLibrary() {
        assumeTrue(NativeCache.locateNxtCacheDll().isPresent(),
                "no NXTCache.dll deployed (~/.botwithus/native/ or -Dnxtcache.dll)");
    }

    private static boolean isCacheSourceOverridden() {
        String path = System.getProperty(CacheSourceResolver.PATH_PROPERTY);
        return (path != null && !path.isBlank())
                || Boolean.getBoolean(CacheSourceResolver.LIVE_PROPERTY);
    }

    /**
     * The shipped-install case: nothing on the command line, and a script's
     * config-type lookup still answers.
     */
    @Test
    void aHostWithNoCachePropertyResolvesItsOwnSourceAndAnswersALookup() throws IOException {
        assumeTrue(!isCacheSourceOverridden(),
                "a cache override is set in this JVM; the override case covers it instead");

        // The defect, still reachable because this method's contract did not change:
        // the properties-only resolver is what both host suppliers used to call, and
        // with no property it hands back null.
        assertNull(NXTCache.tryOpenFromSystemProperty(),
                "if this is non-null the JVM has an override after all and the rest proves nothing");

        // ...and that null is exactly what made every lookup throw at the user.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new GameAPIImpl(null, null).getItemType(COINS_ITEM_ID));
        assertTrue(thrown.getMessage().startsWith("Config-type lookup requires NXTCache"),
                "unexpected message: " + thrown.getMessage());

        // The fix: the same host, same absent flags, resolving its own source.
        CacheSource resolved = new CacheSourceResolver().resolve();
        assertFalse(resolved.isExplicit(), "nothing named this source; it must be discovered");

        try (NXTCache cache = NXTCache.open(resolved)) {
            ItemType coins = new GameAPIImpl(null, cache).getItemType(COINS_ITEM_ID);
            assertNotNull(coins, "config-type lookup came back empty for id " + COINS_ITEM_ID);
            System.out.println("resolved source: " + resolved);
            System.out.println("getItemType(" + COINS_ITEM_ID + ") -> " + coins.name()
                    + " stackable=" + coins.stackable() + " members=" + coins.members());
            assertEquals("Coins", coins.name());
        }
    }

    /**
     * Reports what discovery sees on the machine running the suite, and
     * asserts at least one candidate really holds a cache. This is the case
     * that would have caught a candidate list built for the wrong install
     * shape; the printed list is the diagnostic to paste into a bug report
     * when a user says lookups still fail.
     */
    @Test
    void discoveryFindsACacheOnAMachineWithTheGameInstalled() {
        List<Path> candidates = GameCacheCandidates.forEnvironment(System::getenv);
        System.out.println("candidates on this machine:");
        for (Path candidate : candidates) {
            System.out.println("  " + (CacheSourceResolver.holdsGameCache(candidate) ? "HOLDS " : "empty ")
                    + candidate);
        }
        assumeTrue(!candidates.isEmpty(), "no Windows install roots in this environment");
        assumeTrue(candidates.stream().anyMatch(CacheSourceResolver::holdsGameCache),
                "the game is not installed on this machine, so there is nothing to discover");
        assertTrue(candidates.stream().anyMatch(CacheSourceResolver::holdsGameCache));
    }

    /**
     * The leg a machine with no Jagex-launcher install lands on. Opt-in
     * because it reaches the network: the {@code :core:nxtCacheResolutionTest}
     * task sets the property, the CI-run {@code test} task does not.
     */
    @Test
    void theLiveFallbackAnswersTheSameLookup() throws IOException {
        assumeTrue(Boolean.getBoolean(LIVE_FALLBACK_OPT_IN),
                "set -D" + LIVE_FALLBACK_OPT_IN + "=true to exercise the network leg");

        try (NXTCache cache = NXTCache.open(new CacheSource.Live(false))) {
            ItemType coins = new GameAPIImpl(null, cache).getItemType(COINS_ITEM_ID);
            assertNotNull(coins, "live JS5 came back empty for id " + COINS_ITEM_ID);
            System.out.println("live fallback getItemType(" + COINS_ITEM_ID + ") -> " + coins.name());
            assertEquals("Coins", coins.name());
        }
    }

    /** The dev case: an explicit override still wins, and is still taken at its word. */
    @Test
    void anExplicitPathOverrideStillWins() throws IOException {
        String override = System.getProperty(CacheSourceResolver.PATH_PROPERTY);
        assumeTrue(override != null && !override.isBlank(),
                "no -D" + CacheSourceResolver.PATH_PROPERTY + " set in this JVM");

        CacheSource resolved = new CacheSourceResolver().resolve();
        assertEquals(new CacheSource.LocalDirectory(Path.of(override), true), resolved);

        try (NXTCache cache = NXTCache.open(resolved)) {
            ItemType coins = new GameAPIImpl(null, cache).getItemType(COINS_ITEM_ID);
            assertNotNull(coins);
            System.out.println("override source: " + resolved);
            System.out.println("getItemType(" + COINS_ITEM_ID + ") -> " + coins.name());
            assertEquals("Coins", coins.name());
        }
    }
}
