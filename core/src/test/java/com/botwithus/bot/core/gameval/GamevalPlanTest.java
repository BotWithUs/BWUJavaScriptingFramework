package com.botwithus.bot.core.gameval;

import com.botwithus.bot.core.cache.GamevalIndexVersion;
import com.botwithus.bot.core.cache.NXTCache;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The startup policy, branch by branch, with no DLL, cache or file system. */
class GamevalPlanTest {

    private static final int VERSION = 1729;
    private static final int OTHER_VERSION = 1728;
    private static final int NEGATIVE_VERSION = -12;
    private static final Path LOCAL = Path.of("gameval-local", "gameval-1729.sqlite");
    private static final IntFunction<Optional<Path>> NO_LOCAL = v -> Optional.empty();

    private static GamevalPlan.Probe present(int version) {
        return new GamevalPlan.Probe.Probed(new GamevalIndexVersion.Present(version));
    }

    @Test
    void sameVersionAsTheShippedDb_keepsItWithoutRebuilding() {
        GamevalPlan plan = GamevalPlan.decide(present(VERSION), NO_LOCAL, Optional.of("1729"));

        assertEquals(new GamevalPlan.UseShippedCurrent(VERSION), plan);
    }

    @Test
    void differentVersionFromTheShippedDb_rebuilds() {
        GamevalPlan plan = GamevalPlan.decide(present(VERSION), NO_LOCAL,
                Optional.of(Integer.toString(OTHER_VERSION)));

        assertEquals(new GamevalPlan.BuildThenSwap(VERSION), plan);
    }

    @Test
    void anUnversionedShippedDb_rebuilds() {
        // Every DB baked by the offline Python tool predates meta.index_version.
        GamevalPlan plan = GamevalPlan.decide(present(VERSION), NO_LOCAL, Optional.empty());

        assertEquals(new GamevalPlan.BuildThenSwap(VERSION), plan);
    }

    @Test
    void aNegativeVersion_comparesAsItsDecimalString() {
        GamevalPlan plan = GamevalPlan.decide(present(NEGATIVE_VERSION), NO_LOCAL, Optional.of("-12"));

        assertEquals(new GamevalPlan.UseShippedCurrent(NEGATIVE_VERSION), plan);
    }

    @Test
    void aPaddedShippedVersion_isNotTheSameVersion() {
        // meta stores no padding; a string that merely parses to the same int is not a match.
        GamevalPlan plan = GamevalPlan.decide(present(VERSION), NO_LOCAL, Optional.of("01729"));

        assertEquals(new GamevalPlan.BuildThenSwap(VERSION), plan);
    }

    @Test
    void aLocalBuildOfTheProbedVersion_winsOverTheShippedDb() {
        IntFunction<Optional<Path>> local = v -> v == VERSION ? Optional.of(LOCAL) : Optional.empty();

        GamevalPlan plan = GamevalPlan.decide(present(VERSION), local, Optional.of("1729"));

        assertEquals(new GamevalPlan.UseLocal(LOCAL, VERSION), plan);
    }

    @Test
    void aLocalBuildOfAnotherVersion_isIgnored() {
        IntFunction<Optional<Path>> local =
                v -> v == OTHER_VERSION ? Optional.of(LOCAL) : Optional.empty();

        GamevalPlan plan = GamevalPlan.decide(present(VERSION), local, Optional.empty());

        assertEquals(new GamevalPlan.BuildThenSwap(VERSION), plan);
    }

    @Test
    void notFound_keepsTheShippedDb() {
        GamevalPlan plan = GamevalPlan.decide(
                new GamevalPlan.Probe.Probed(new GamevalIndexVersion.Absent()), NO_LOCAL, Optional.empty());

        GamevalPlan.UseShipped shipped = assertInstanceOf(GamevalPlan.UseShipped.class, plan);
        assertTrue(shipped.reason().contains("no gameval index"), shipped.reason());
    }

    @Test
    void aFailedProbe_keepsTheShippedDb() {
        GamevalPlan plan = GamevalPlan.decide(
                new GamevalPlan.Probe.Probed(new GamevalIndexVersion.Failed(NXTCache.NXT_ERR_IO, "connection lost")),
                NO_LOCAL, Optional.empty());

        GamevalPlan.UseShipped shipped = assertInstanceOf(GamevalPlan.UseShipped.class, plan);
        assertTrue(shipped.reason().contains("rc=4: connection lost"), shipped.reason());
    }

    @Test
    void aMissingSymbol_keepsTheShippedDbAndSaysWhy() {
        String reason = "this NXTCache.dll predates nxt_build_gameval_db";

        GamevalPlan plan = GamevalPlan.decide(new GamevalPlan.Probe.Unavailable(reason),
                v -> {
                    throw new AssertionError("no version, so no local lookup");
                }, Optional.of("1729"));

        assertEquals(new GamevalPlan.UseShipped(reason), plan);
    }
}
