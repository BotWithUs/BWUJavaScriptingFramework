package com.botwithus.bot.core.shm;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Decodes an entity server index the producer writes as a 16-bit field with {@code -1} meaning "none"
 * (following, target, projectile source and target).
 *
 * <p>Server indices run past {@link Short#MAX_VALUE}, so the field has to be read unsigned: read as a
 * signed short, an NPC at server index 37244 comes back as {@code -28292} and never equals
 * {@code Npc.serverIndex()}, so a script cannot tell it is the entity the player is fighting.
 * {@code 0xFFFF} is the producer's {@code -1} and stays {@code -1}.
 */
final class EntityIndexWire {

    /** The unsigned value of the producer's {@code -1} "no entity" sentinel. */
    static final int NONE_WIRE = 0xFFFF;
    /** The decoded "no entity" value every index accessor promises. */
    static final int NONE = -1;

    private EntityIndexWire() {
    }

    /** The server index stored at {@code offset}, or {@link #NONE} when the field holds the sentinel. */
    static int read(MemorySegment seg, long offset) {
        return decode(seg.get(ValueLayout.JAVA_SHORT, offset));
    }

    /** The server index a raw 16-bit field encodes, or {@link #NONE} for the sentinel. */
    static int decode(short raw) {
        int unsigned = Short.toUnsignedInt(raw);
        return unsigned == NONE_WIRE ? NONE : unsigned;
    }
}
