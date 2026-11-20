package com.botwithus.bot.api.entities;

import java.util.function.Predicate;

/**
 * The option filter for a query over {@link Interactive} entities.
 *
 * @param <T> entity type
 * @param <Q> the query's own type, for chaining
 */
public interface OptionFilters<T extends Interactive, Q> {

    /** Adds a predicate; implemented by {@link EntityQuery#filter}. */
    Q filter(Predicate<T> predicate);

    /**
     * Entities offering {@code option} on their right-click menu, ignoring case. Reading the
     * options resolves the entity's definition, which costs one lookup per new type id.
     */
    default Q withOption(String option) {
        return filter(t -> t.hasOption(option));
    }
}
