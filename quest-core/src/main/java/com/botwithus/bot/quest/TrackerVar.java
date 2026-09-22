package com.botwithus.bot.quest;

import java.util.Objects;

/**
 * One variable a quest's progress is tracked in, with its kind.
 *
 * <p>The kind is not decoration. Varps and varbits are separate id spaces: varp 297
 * and varbit 297 are unrelated variables. A tracker that only knew the number had
 * to read each id as both and guess which answer was real, and it routed a change
 * to either one into the same slot.</p>
 *
 * @param kind which id space {@code id} belongs to
 * @param id   the varp or varbit id
 */
public record TrackerVar(Kind kind, int id) {

    /** The two id spaces a quest can be tracked in. */
    public enum Kind {
        VARP,
        VARBIT
    }

    public TrackerVar {
        Objects.requireNonNull(kind, "kind");
    }

    /** A player variable. */
    public static TrackerVar varp(int id) {
        return new TrackerVar(Kind.VARP, id);
    }

    /** A variable bit. */
    public static TrackerVar varbit(int id) {
        return new TrackerVar(Kind.VARBIT, id);
    }
}
