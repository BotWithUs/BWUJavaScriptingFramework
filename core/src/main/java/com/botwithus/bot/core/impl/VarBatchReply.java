package com.botwithus.bot.core.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.botwithus.bot.core.impl.MapHelper.getIntList;
import static com.botwithus.bot.core.impl.MapHelper.getStringList;

/**
 * A {@code get_varps} reply read with its per-id presence, whichever shape the agent sent.
 *
 * <p>Presence is the predicate and {@code values} the message; a value is never used to
 * decide presence (an unset varp reads 0 on a current agent and -1 on an older one, and a
 * set varp can legitimately hold either). The reply shapes, newest first:</p>
 * <ul>
 *   <li>{@code states[]}: present exactly when the state is {@link #STATE_PRESENT}. State 1
 *       (absent, the varp holds its type default) and state 0 (the read could not be made)
 *       are both "not present" here.</li>
 *   <li>{@code found[]} only (an agent from before {@code states}): present exactly when
 *       found is {@code true}. The contract is {@code found == (state == 2)}, so that
 *       direction is exact; {@code false} could be either "absent" or "failed", so it
 *       fails closed as not present.</li>
 *   <li>neither: nothing is known to be present.</li>
 * </ul>
 * <p>Ids past the end of any array are not present, so a truncated batch never pairs an id
 * with a neighbour's value.</p>
 */
record VarBatchReply(List<Integer> values, List<Boolean> present) {

    /** The wire's permanent number for "the value is the stored one". */
    static final int STATE_PRESENT = 2;

    private static final String VALUES = "values";
    private static final String STATES = "states";
    private static final String FOUND = "found";

    VarBatchReply {
        values = List.copyOf(values);
        present = List.copyOf(present);
    }

    static VarBatchReply parse(Map<String, Object> reply) {
        List<Integer> values = getIntList(reply, VALUES);
        if (reply.containsKey(STATES)) {
            return new VarBatchReply(values, presentFromStates(getIntList(reply, STATES)));
        }
        if (reply.containsKey(FOUND)) {
            // Read through MapHelper, the one place allowed to narrow the msgpack Object
            // graph; a Boolean element renders as "true" / "false".
            return new VarBatchReply(values, presentFromFound(getStringList(reply, FOUND)));
        }
        return new VarBatchReply(values, List.of());
    }

    /** True when the agent reported id {@code i}'s stored value. */
    boolean isPresent(int i) {
        return i < values.size() && i < present.size() && present.get(i);
    }

    int value(int i) {
        return values.get(i);
    }

    private static List<Boolean> presentFromStates(List<Integer> states) {
        List<Boolean> out = new ArrayList<>(states.size());
        for (int state : states) {
            out.add(state == STATE_PRESENT);
        }
        return out;
    }

    private static List<Boolean> presentFromFound(List<String> found) {
        List<Boolean> out = new ArrayList<>(found.size());
        for (String f : found) {
            out.add(Boolean.TRUE.toString().equals(f));
        }
        return out;
    }
}
