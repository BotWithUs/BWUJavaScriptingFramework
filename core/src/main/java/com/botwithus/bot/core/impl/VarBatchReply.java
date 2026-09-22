package com.botwithus.bot.core.impl;

import java.util.List;

/**
 * One batched variable read ({@code get_varps} / {@code get_varcs_int}) as the
 * producer returns it: the values parallel to the requested ids, and the per-id
 * flags saying whether the variable domain actually held a node for that id.
 *
 * <p>Neither array is compacted — both are parallel to the request — and a miss
 * is reported as {@code found[i] == false} alongside a {@code -1} in
 * {@code values[i]}. That {@code -1} is a <em>placeholder</em>, not a value:
 * shifting it into a varbit's bit range yields all-ones for that range, so a
 * single-bit flag read out of an absent base decodes as set. Anything that
 * needs to tell "absent" from "read back as -1" must consult {@link #found()};
 * the raw varp/varc accessors deliberately do not, because {@code -1} is their
 * own documented "unset" sentinel.</p>
 *
 * @param values one value per requested id, in request order
 * @param found  one flag per requested id, in request order
 */
record VarBatchReply(List<Integer> values, List<Boolean> found) {

    /**
     * How many request slots are safely readable. A producer-side truncation
     * must leave the dropped ids <em>absent</em> rather than mispaired with
     * another id's value, so the usable prefix is bounded by the shortest of
     * the request and the two reply arrays.
     *
     * @param requestedCount how many ids were asked for
     * @return the number of leading slots for which id, value and flag all exist
     */
    int pairedCount(int requestedCount) {
        return Math.min(requestedCount, Math.min(values.size(), found.size()));
    }
}
