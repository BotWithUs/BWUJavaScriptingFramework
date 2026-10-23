package com.botwithus.bot.cli.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The host's settings: one typed store over {@code ~/.botwithus/config.properties}.
 *
 * <p><b>Instant save.</b> Every change is kept in memory at once and written
 * {@value #SAVE_DEBOUNCE_MS} ms later on a virtual thread, so a burst of changes
 * (a slider drag, a number typed digit by digit) becomes one write. The write is
 * atomic (temp file + move). {@link #status()} reports saving / saved / failed
 * for the Settings header; {@link #flush()} writes now and is what a command that
 * prints "saved" or a shutdown path calls.</p>
 *
 * <p><b>Typed and validated.</b> Values are read and written through a
 * {@link SettingKey}, which carries the default, the bounds and the description.
 * A value outside its bounds is refused with an {@link InvalidSettingException}
 * whose message names the key and the rule. A value in the file that is invalid
 * is logged and ignored (the default applies). A name in the file that is not in
 * the catalogue is kept as-is and reported by {@link #unknownEntries()}.</p>
 *
 * <p><b>Live.</b> {@link #onChange} and {@link #onAnyChange} listeners run
 * synchronously on whichever thread made the change, after the change is visible
 * to {@link #get}, and only when the effective value actually changed.</p>
 *
 * <p>One instance per process, created by the composition root
 * ({@link #openForHost(Path)}) and passed to whatever needs it. Thread-safe.</p>
 */
public final class HostSettings implements AutoCloseable {

    /** Settings file name inside the data folder. */
    public static final String FILE_NAME = "config.properties";

    /** How long a change waits for further changes before it is written. */
    public static final long SAVE_DEBOUNCE_MS = 250L;

    private static final Logger log = LoggerFactory.getLogger(HostSettings.class);
    private static final String DATA_FOLDER_NAME = ".botwithus";
    private static final Duration SAVE_DEBOUNCE = Duration.ofMillis(SAVE_DEBOUNCE_MS);

    /** Waits out the debounce window. A seam so tests can hold a save open. */
    @FunctionalInterface
    interface Pause {
        void await() throws InterruptedException;
    }

    private final SettingsStorage storage;
    private final List<SettingKey<?>> keys;
    private final Map<String, SettingKey<?>> keysByName;
    private final Pause savePause;
    private final Clock clock;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final List<Consumer<? super SettingChange>> listeners = new CopyOnWriteArrayList<>();
    private final Object stateLock = new Object();
    private final Object writeLock = new Object();

    // Guarded by stateLock.
    private long changeCount;
    private long writtenCount;
    private boolean isSaveScheduled;
    private volatile SaveStatus status;

    HostSettings(SettingsStorage storage, List<SettingKey<?>> keys, Pause savePause, Clock clock) {
        this.storage = storage;
        this.keys = List.copyOf(keys);
        this.keysByName = indexByName(this.keys);
        this.savePause = savePause;
        this.clock = clock;
        loadInitial();
    }

    /** {@code ~/.botwithus}, the host's data folder. */
    public static Path defaultBaseDir() {
        return Path.of(System.getProperty("user.home"), DATA_FOLDER_NAME);
    }

    /** Opens {@code <baseDir>/config.properties} against {@link SettingKeys#ALL}. No migration, no hooks. */
    public static HostSettings open(Path baseDir) {
        return new HostSettings(new PropertiesFileStorage(baseDir.resolve(FILE_NAME)), SettingKeys.ALL,
                () -> Thread.sleep(SAVE_DEBOUNCE), Clock.systemUTC());
    }

    /**
     * Opens the settings for a host process: {@link #open}, then the one-shot
     * {@link SettingsMigration} of the legacy auto-start globals, then a JVM
     * shutdown hook that writes any change still inside its debounce window, so
     * closing the window or {@code exit} right after a change does not lose it.
     * Call once per process, from the composition root.
     */
    public static HostSettings openForHost(Path baseDir) {
        HostSettings settings = open(baseDir);
        SettingsMigration.run(baseDir, settings);
        // A platform thread, not virtual: shutdown hooks run while the JVM is
        // tearing down, and a plain OS thread is the conservative choice there.
        Runtime.getRuntime().addShutdownHook(
                Thread.ofPlatform().name("settings-flush").unstarted(settings::flush));
        return settings;
    }

    // ── Reading ─────────────────────────────────────────────────────────

    /** The effective value of {@code key}: what the file sets, else its default. */
    public <T> T get(SettingKey<T> key) {
        String text = values.get(key.name());
        return text == null ? key.defaultValue() : key.parse(text);
    }

    /** The effective value of {@code key} in its text form. */
    public String text(SettingKey<?> key) {
        String text = values.get(key.name());
        return text == null ? key.formattedDefault() : text;
    }

    /** {@code true} when the file sets {@code key} explicitly (even to its default). */
    public boolean isExplicit(SettingKey<?> key) {
        return values.containsKey(key.name());
    }

    /** Every known key, in settings-page order. */
    public List<SettingKey<?>> keys() {
        return keys;
    }

    /** The known key called {@code name}, if any. */
    public Optional<SettingKey<?>> find(String name) {
        return Optional.ofNullable(keysByName.get(name));
    }

    /** Entries in the file that no known key claims, sorted by name. Kept on every save. */
    public Map<String, String> unknownEntries() {
        Map<String, String> unknown = new TreeMap<>();
        values.forEach((name, text) -> {
            if (!keysByName.containsKey(name)) {
                unknown.put(name, text);
            }
        });
        return Collections.unmodifiableMap(unknown);
    }

    /** The settings file. */
    public Path file() {
        return storage.location();
    }

    /** Whether the file on disk is up to date. */
    public SaveStatus status() {
        return status;
    }

    // ── Writing ─────────────────────────────────────────────────────────

    /**
     * Sets {@code key} to {@code value} and schedules a save.
     *
     * @throws InvalidSettingException  if {@code value} is outside the key's bounds
     * @throws IllegalArgumentException if {@code key} is not in this store's catalogue
     */
    public <T> void set(SettingKey<T> key, T value) {
        requireKnown(key);
        key.validate(value);
        apply(key, key.format(value));
    }

    /**
     * Sets the key called {@code name} from text, as {@code config set} and the
     * "All config keys" table do.
     *
     * @return the key that was set
     * @throws InvalidSettingException if no known key has that name, or the text is not a valid value
     */
    public SettingKey<?> setText(String name, String text) {
        Objects.requireNonNull(text, "text");
        SettingKey<?> key = keysByName.get(name);
        if (key == null) {
            throw new InvalidSettingException(name, "is not a known setting");
        }
        apply(key, normalise(key, text));
        return key;
    }

    /** Removes {@code key} from the file so its default applies again. */
    public void reset(SettingKey<?> key) {
        requireKnown(key);
        apply(key, null);
    }

    /**
     * Calls {@code listener} with the new value whenever {@code key}'s effective
     * value changes. See the class comment for threading.
     */
    public <T> Subscription onChange(SettingKey<T> key, Consumer<? super T> listener) {
        Objects.requireNonNull(listener, "listener");
        return onAnyChange(change -> {
            if (change.key().name().equals(key.name())) {
                listener.accept(key.parse(change.newValue()));
            }
        });
    }

    /** Calls {@code listener} for every change to any setting's effective value. */
    public Subscription onAnyChange(Consumer<? super SettingChange> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Writes any pending change now, on the calling thread, and returns the
     * resulting status. Cheap when nothing is pending.
     */
    public SaveStatus flush() {
        writeIfDirty();
        return status;
    }

    /** Same as {@link #flush()}; the store stays usable. */
    @Override
    public void close() {
        flush();
    }

    // ── Internals ───────────────────────────────────────────────────────

    private void loadInitial() {
        Map<String, String> entries;
        try {
            entries = storage.load();
        } catch (IOException e) {
            log.error("Could not read {}: {}", storage.location(), e.getMessage());
            status = new SaveStatus.Failed("Could not read settings: " + e.getMessage(), clock.instant());
            return;
        }
        entries.forEach(this::loadEntry);
        status = new SaveStatus.Saved(clock.instant());
    }

    private void loadEntry(String name, String text) {
        SettingKey<?> key = keysByName.get(name);
        if (key == null) {
            values.put(name, text);
            return;
        }
        try {
            values.put(name, normalise(key, text));
        } catch (InvalidSettingException e) {
            log.warn("Ignoring {} in {}; using the default {}", e.getMessage(), storage.location(),
                    key.formattedDefault());
        }
    }

    private void apply(SettingKey<?> key, String newText) {
        SettingChange change;
        synchronized (stateLock) {
            String before = text(key);
            String previous = newText == null ? values.remove(key.name()) : values.put(key.name(), newText);
            if (Objects.equals(previous, newText)) {
                return;
            }
            markDirty();
            String after = text(key);
            change = before.equals(after) ? null : new SettingChange(key, before, after);
        }
        if (change != null) {
            notifyListeners(change);
        }
    }

    /** Caller holds {@link #stateLock}. */
    private void markDirty() {
        changeCount++;
        status = new SaveStatus.Saving();
        if (!isSaveScheduled) {
            isSaveScheduled = true;
            Thread.ofVirtual().name("settings-save").start(this::debouncedSave);
        }
    }

    private void debouncedSave() {
        try {
            savePause.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (stateLock) {
            isSaveScheduled = false;
        }
        writeIfDirty();
    }

    private void writeIfDirty() {
        synchronized (writeLock) {
            long target;
            Map<String, String> snapshot;
            synchronized (stateLock) {
                if (writtenCount == changeCount) {
                    return;
                }
                target = changeCount;
                snapshot = new TreeMap<>(values);
            }
            try {
                storage.save(snapshot);
                recordWritten(target);
            } catch (IOException | RuntimeException e) {
                recordFailure(e);
            }
        }
    }

    private void recordWritten(long target) {
        synchronized (stateLock) {
            writtenCount = target;
            if (writtenCount == changeCount) {
                status = new SaveStatus.Saved(clock.instant());
            }
        }
    }

    private void recordFailure(Exception e) {
        log.warn("Could not save {}: {}", storage.location(), e.toString());
        synchronized (stateLock) {
            status = new SaveStatus.Failed("Could not save settings: " + e.getMessage(), clock.instant());
        }
    }

    private void notifyListeners(SettingChange change) {
        for (Consumer<? super SettingChange> listener : listeners) {
            try {
                listener.accept(change);
            } catch (RuntimeException e) {
                log.warn("Settings listener failed on {}: {}", change.key().name(), e.toString(), e);
            }
        }
    }

    private void requireKnown(SettingKey<?> key) {
        if (!key.equals(keysByName.get(key.name()))) {
            throw new IllegalArgumentException("'" + key.name() + "' is not in this store's catalogue");
        }
    }

    private static <T> String normalise(SettingKey<T> key, String text) {
        return key.format(key.parse(text));
    }

    private static Map<String, SettingKey<?>> indexByName(List<SettingKey<?>> keys) {
        Map<String, SettingKey<?>> byName = new LinkedHashMap<>();
        for (SettingKey<?> key : keys) {
            if (byName.put(key.name(), key) != null) {
                throw new IllegalArgumentException("duplicate setting '" + key.name() + "'");
            }
        }
        return Collections.unmodifiableMap(byName);
    }
}
