package com.botwithus.bot.api.entities;

import java.util.List;

/** Something with a right-click menu whose option names are known to the host. */
public interface Interactive {

    /** Right-click option names, in menu order; empty when unknown. */
    List<String> getOptions();

    /** Whether any option equals {@code option}, ignoring case. */
    default boolean hasOption(String option) {
        for (String o : getOptions()) {
            if (o != null && o.equalsIgnoreCase(option)) {
                return true;
            }
        }
        return false;
    }
}
