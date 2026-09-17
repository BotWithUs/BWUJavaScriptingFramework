package com.botwithus.bot.core.worldwalker;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sparse capability inputs that gate requirement-checked transitions during a
 * route query. Mirrors the C ABI {@code WwCapabilitySnapshot}: four (id →
 * value) runs covering skills, items, varbits, and varps.
 *
 * <p>Built with {@link Builder}; pass {@link #empty()} (or {@code null} to
 * {@link WorldWalker#query}) to admit every requirement-gated transition.</p>
 *
 * <p>The Java side keeps the runs as {@code LinkedHashMap}s so insertion order
 * is preserved when serialised into Panama memory — handy when a future
 * change wants stable artifact-trace logging keyed on the snapshot.</p>
 */
public record CapabilitySnapshot(
        Map<Integer, Integer> skills,
        Map<Integer, Integer> items,
        Map<Integer, Integer> varbits,
        Map<Integer, Integer> varps) {

    public CapabilitySnapshot {
        skills  = Collections.unmodifiableMap(new LinkedHashMap<>(skills));
        items   = Collections.unmodifiableMap(new LinkedHashMap<>(items));
        varbits = Collections.unmodifiableMap(new LinkedHashMap<>(varbits));
        varps   = Collections.unmodifiableMap(new LinkedHashMap<>(varps));
    }

    private static final CapabilitySnapshot EMPTY = new CapabilitySnapshot(
            Map.of(), Map.of(), Map.of(), Map.of());

    public static CapabilitySnapshot empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return skills.isEmpty() && items.isEmpty() && varbits.isEmpty() && varps.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final Map<Integer, Integer> skills  = new LinkedHashMap<>();
        private final Map<Integer, Integer> items   = new LinkedHashMap<>();
        private final Map<Integer, Integer> varbits = new LinkedHashMap<>();
        private final Map<Integer, Integer> varps   = new LinkedHashMap<>();

        private Builder() {}

        public Builder skill(int id, int level)   { skills.put(id, level);   return this; }
        public Builder item(int id, int count)    { items.put(id, count);    return this; }
        public Builder varbit(int id, int value)  { varbits.put(id, value);  return this; }
        public Builder varp(int id, int value)    { varps.put(id, value);    return this; }

        public CapabilitySnapshot build() {
            return new CapabilitySnapshot(skills, items, varbits, varps);
        }
    }
}
