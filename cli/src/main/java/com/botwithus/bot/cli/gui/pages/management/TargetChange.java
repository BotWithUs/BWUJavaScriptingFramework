package com.botwithus.bot.cli.gui.pages.management;

import com.botwithus.bot.cli.management.Target;

import java.util.Objects;

/** One change to a management script's targets, as the Applies to list makes it. */
public sealed interface TargetChange {

    /** The target added or removed. */
    Target target();

    /** Starts managing {@code target}; the whole host replaces every other target. */
    record Add(Target target) implements TargetChange {
        public Add {
            Objects.requireNonNull(target, "target");
        }
    }

    /** Stops managing {@code target}. */
    record Remove(Target target) implements TargetChange {
        public Remove {
            Objects.requireNonNull(target, "target");
        }
    }
}
