package com.botwithus.bot.core.crypto;

import java.util.List;

/**
 * One courier delivery after its jars were handed to the runtime: a loader for
 * each jar that defined, and where in the delivery each one sat.
 *
 * <p>The position is the point of this type. The launcher delivers one jar per
 * requested script, in the order requested, and the host pairs a jar with its
 * request by that position. A jar that fails to define is skipped, so the list
 * index is <em>not</em> the position, and {@link #jarCount()} still counts it.
 *
 * @param jarCount how many jars the delivery carried, defined or not
 * @param jars     one entry per jar that defined, in delivery order
 */
public record DefinedBundle(int jarCount, List<Jar> jars) {

    public DefinedBundle {
        jars = List.copyOf(jars);
    }

    /** A delivery that never arrived, or could not be read. */
    public static DefinedBundle none() {
        return new DefinedBundle(0, List.of());
    }

    /**
     * One defined jar.
     *
     * @param position the jar's zero-based place in the delivery
     * @param loader   the loader backing it
     */
    public record Jar(int position, ClassLoader loader) {
    }
}
