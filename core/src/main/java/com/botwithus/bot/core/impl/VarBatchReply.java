package com.botwithus.bot.core.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.botwithus.bot.core.impl.MapHelper.getBoolList;
import static com.botwithus.bot.core.impl.MapHelper.getIntList;

/**
 * One batched variable read ({@code get_varps} / {@code get_varcs_int}) as the producer
 * returns it: the values parallel to the requested ids, and for each id whether the value
 * is real.
 *
 * <p><b>Presence is the predicate and {@code values} the message.</b> A value never
 * decides presence: an absent varp is reported as {@code 0} by a current agent and as a
 * {@code -1} placeholder by an older one, and a present varp can legitimately hold
 * either. Shifting a placeholder into a varbit's bit range would read as all ones, so a
 * single-bit flag out of an absent base would decode as set.</p>
 *
 * <p>Presence comes from whichever array the reply carries, newest first:</p>
 * <ul>
 *   <li>{@code states[]} ({@code get_varps} from an agent with per-id state):
 *       {@code 2} {@link Presence#PRESENT}, {@code 1} {@link Presence#ABSENT} (the domain
 *       has no node; the variable holds its type default), {@code 0} and anything else
 *       {@link Presence#UNAVAILABLE} (the read could not be made: not in game, bad id,
 *       timeout). It says nothing about the variable.</li>
 *   <li>{@code found[]} only ({@code get_varcs_int}, which sends no states, or an older
 *       agent): {@code true} is {@link Presence#PRESENT}, which is exact by the contract
 *       {@code found == (state == 2)}. {@code false} is {@link Presence#ABSENT}: this reply
 *       cannot tell a miss from a failed read, and absent is the engine's own reading of
 *       a missing node.</li>
 *   <li>neither: no slot is paired, so nothing reads as present.</li>
 * </ul>
 *
 * <p>Neither array is compacted: both are parallel to the request. A truncated reply leaves
 * the ids past the shortest array unpaired ({@link #pairedCount}) rather than mispaired with
 * a neighbour's value.</p>
 *
 * <p>A {@code get_varps} reply from a current agent also carries {@code values64[]} (the
 * full value: a LONG varp's 64 bits, anything else sign-extended) and {@code kinds[]} (how
 * a present value is stored). Replies without them read as the 32-bit value sign-extended
 * and an unknown kind.</p>
 *
 * @param values   one value per requested id, in request order (low 32 bits)
 * @param presence one presence per requested id, in request order
 * @param values64 full-width values, parallel; empty when the reply has none
 * @param kinds    the stored kind of each present value, parallel; empty when none
 */
record VarBatchReply(List<Integer> values, List<Presence> presence, List<Long> values64,
                     List<Integer> kinds) {

    /** Whether a slot's value is real, a default, or not known at all. */
    enum Presence {
        /** The stored value was read. */
        PRESENT,
        /** The domain has no node for the id; the variable holds its type default. */
        ABSENT,
        /** The read could not be made; nothing is known about the variable. */
        UNAVAILABLE
    }

    /** The wire's permanent state numbers. */
    static final int STATE_ABSENT = 1;
    static final int STATE_PRESENT = 2;

    private static final String VALUES = "values";
    private static final String STATES = "states";
    private static final String FOUND = "found";
    private static final String VALUES64 = "values64";
    private static final String KINDS = "kinds";

    /** {@link #kindAt} when the reply carries no kind for the slot. */
    static final int KIND_UNREPORTED = -1;

    VarBatchReply {
        values = List.copyOf(values);
        presence = List.copyOf(presence);
        values64 = List.copyOf(values64);
        kinds = List.copyOf(kinds);
    }

    VarBatchReply(List<Integer> values, List<Presence> presence) {
        this(values, presence, List.of(), List.of());
    }

    static VarBatchReply empty() {
        return new VarBatchReply(List.of(), List.of());
    }

    static VarBatchReply parse(Map<String, Object> reply) {
        List<Integer> values = getIntList(reply, VALUES);
        List<Presence> presence = reply.containsKey(STATES)
                ? fromStates(getIntList(reply, STATES))
                : fromFound(getBoolList(reply, FOUND));
        return new VarBatchReply(values, presence,
                MapHelper.getLongList(reply, VALUES64), getIntList(reply, KINDS));
    }

    /** Slot {@code i}'s full-width value; the 32-bit value sign-extended when none was sent. */
    long value64(int i) {
        return i < values64.size() ? values64.get(i) : values.get(i);
    }

    /** Slot {@code i}'s stored kind as the wire numbers it, or {@link #KIND_UNREPORTED}. */
    int kindAt(int i) {
        return i < kinds.size() ? kinds.get(i) : KIND_UNREPORTED;
    }

    /**
     * How many request slots are safely readable: the shortest of the request and the two
     * reply arrays, so a producer-side truncation leaves the dropped ids unpaired.
     *
     * @param requestedCount how many ids were asked for
     */
    int pairedCount(int requestedCount) {
        return Math.min(requestedCount, Math.min(values.size(), presence.size()));
    }

    /** The presence of slot {@code i}; {@link Presence#UNAVAILABLE} for an unpaired slot. */
    Presence presenceAt(int i) {
        return i < values.size() && i < presence.size() ? presence.get(i) : Presence.UNAVAILABLE;
    }

    /** True when the agent reported slot {@code i}'s stored value. */
    boolean isPresent(int i) {
        return presenceAt(i) == Presence.PRESENT;
    }

    int value(int i) {
        return values.get(i);
    }

    private static List<Presence> fromStates(List<Integer> states) {
        List<Presence> out = new ArrayList<>(states.size());
        for (int state : states) {
            out.add(state == STATE_PRESENT ? Presence.PRESENT
                    : state == STATE_ABSENT ? Presence.ABSENT
                    : Presence.UNAVAILABLE);
        }
        return out;
    }

    private static List<Presence> fromFound(List<Boolean> found) {
        List<Presence> out = new ArrayList<>(found.size());
        for (boolean f : found) {
            out.add(f ? Presence.PRESENT : Presence.ABSENT);
        }
        return out;
    }
}
