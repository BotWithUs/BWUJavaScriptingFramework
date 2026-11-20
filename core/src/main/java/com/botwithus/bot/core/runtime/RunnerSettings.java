package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.config.ScriptConfig;
import com.botwithus.bot.api.config.ScriptSettings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * One script run's settings, and the runner's current {@link ScriptConfig} with them: they
 * are one map, so a key the script puts here and a field the user edits in the UI are saved
 * together and neither save loses the other's keys.
 *
 * <p>Created with the script's context, before its runner exists, and handed to both.
 * Copy-on-write over an immutable {@link ScriptConfig}, so readers on any thread see a whole
 * map.</p>
 */
public final class RunnerSettings implements ScriptSettings {

    private static final ScriptConfig EMPTY = new ScriptConfig(Map.of());

    private final AtomicReference<ScriptConfig> config = new AtomicReference<>(EMPTY);
    private volatile Consumer<ScriptConfig> persister = ignored -> { };

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(config.get().asMap().get(key));
    }

    @Override
    public void put(String key, String value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("key and value must not be null");
        }
        update(values -> {
            values.put(key, value);
            return values;
        });
    }

    @Override
    public boolean remove(String key) {
        boolean[] removed = new boolean[1];
        update(values -> {
            removed[0] = values.remove(key) != null;
            return values;
        });
        return removed[0];
    }

    @Override
    public Map<String, String> asMap() {
        return config.get().asMap();
    }

    @Override
    public void save() {
        persister.accept(config.get());
    }

    /** The current values as the config the script's {@code onConfigUpdate} is handed. */
    public ScriptConfig current() {
        return config.get();
    }

    /** Replaces every value, as a load from disk does. */
    public void replace(ScriptConfig loaded) {
        config.set(loaded == null ? EMPTY : loaded);
    }

    /**
     * Lays {@code applied} over the current values, as a UI apply does: its keys win, and
     * keys it does not carry (the script's own) are kept.
     *
     * @return the merged config
     */
    public ScriptConfig merge(ScriptConfig applied) {
        return update(values -> {
            values.putAll(applied.asMap());
            return values;
        });
    }

    /** Where {@link #save()} writes; installed by the runner once it knows the account. */
    public void setPersister(Consumer<ScriptConfig> persister) {
        this.persister = persister == null ? ignored -> { } : persister;
    }

    private ScriptConfig update(UnaryOperator<Map<String, String>> change) {
        return config.updateAndGet(old -> new ScriptConfig(change.apply(new LinkedHashMap<>(old.asMap()))));
    }
}
