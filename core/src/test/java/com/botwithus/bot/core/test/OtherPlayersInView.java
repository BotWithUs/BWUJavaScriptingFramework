package com.botwithus.bot.core.test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The "another player is in view" precondition, for live tests that read other players' rows.
 *
 * <p>Whether anyone else is standing nearby is a fact about the world the character is on, not
 * about the code under test: a harness client on a quiet world sees nobody, and that must skip.
 * A skip must still never hide a broken read, so the two steps are separate. {@link #othersOf}
 * first checks that the read worked, using the local player's own row as the positive control:
 * the producer always publishes it in {@code players[]}, so a read that lacks it is a failure
 * whatever else it holds. Only then may {@link #requireAny} turn an empty result into a skip.</p>
 */
public final class OtherPlayersInView {

    private OtherPlayersInView() {
    }

    /**
     * The rows in {@code all} other than the local player's, after checking that {@code all}
     * holds the local player's row.
     *
     * @param all         every row the read returned, the local player's included
     * @param serverIndex reads a row's server index
     * @param ownIndex    the local player's server index, from the snapshot's {@code self()}
     * @return the other players' rows, possibly empty
     */
    public static <T> List<T> othersOf(List<T> all, ToIntFunction<T> serverIndex, int ownIndex) {
        if (all.stream().noneMatch(row -> serverIndex.applyAsInt(row) == ownIndex)) {
            return fail("the players[] read is broken, not empty: " + all.size()
                    + " rows and none is the local player's (index " + ownIndex + ")");
        }
        return all.stream().filter(row -> serverIndex.applyAsInt(row) != ownIndex).toList();
    }

    /**
     * Skips the calling test when {@code others} is empty.
     *
     * @param others the result of {@link #othersOf}
     * @param where  where the test looked, for the skip reason
     * @return {@code others}, which is non-empty if this returns
     */
    public static <T> List<T> requireAny(List<T> others, String where) {
        assumeTrue(!others.isEmpty(), () -> "SKIPPED, not passed: no other player in view " + where
                + ". The players[] read worked (it held the local player's row); the world is just "
                + "quiet. Stand somewhere busier, or on a populated world, to run this test.");
        return others;
    }
}
