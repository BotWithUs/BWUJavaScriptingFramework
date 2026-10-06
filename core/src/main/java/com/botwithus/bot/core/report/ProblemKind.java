package com.botwithus.bot.core.report;

import java.util.Arrays;
import java.util.Optional;

/**
 * What the user says went wrong. A report must name one; the website groups
 * reports without a crash by it.
 */
public enum ProblemKind {
    CRASHED("crashed", "It stopped with an error"),
    STUCK("stuck", "It got stuck or stopped doing anything"),
    WRONG_ACTION("wrong_action", "It did the wrong thing"),
    OTHER("other", "Something else");

    private final String wireName;
    private final String label;

    ProblemKind(String wireName, String label) {
        this.wireName = wireName;
        this.label = label;
    }

    /** The value the request carries. */
    public String wireName() {
        return wireName;
    }

    /** The words the user picks it by. */
    public String label() {
        return label;
    }

    /** The kind spelled {@code wireName}, if any. */
    public static Optional<ProblemKind> fromWire(String wireName) {
        return Arrays.stream(values()).filter(k -> k.wireName.equals(wireName)).findFirst();
    }
}
