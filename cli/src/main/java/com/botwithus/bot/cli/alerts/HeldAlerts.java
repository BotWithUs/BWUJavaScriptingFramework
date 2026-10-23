package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.Alert;
import com.botwithus.bot.core.alerts.AlertKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The alerts quiet hours are holding back to send when they end, kept in
 * {@value #FILE_NAME} in the host's data folder so that a host restart during
 * quiet hours does not lose them.
 *
 * <p>At most {@link #MAX_KEPT} alerts are kept, oldest first; any after that are
 * only counted, by kind, so the file stays small however long the night.
 * Thread-safe. Every change is saved before the call returns; a save that fails is
 * logged and the alerts stay held in memory.</p>
 */
public final class HeldAlerts {

    /** The file's name in the host's data folder. */
    public static final String FILE_NAME = "held-alerts.json";

    /** Alerts kept with their text; later ones are only counted. */
    public static final int MAX_KEPT = 200;

    private static final Logger log = LoggerFactory.getLogger(HeldAlerts.class);

    private final HeldAlertsFile file;
    private final Object lock = new Object();
    /** Guarded by {@link #lock}. */
    private final List<Alert> kept = new ArrayList<>();
    /** Guarded by {@link #lock}. */
    private final Map<AlertKind, Integer> overflow = new EnumMap<>(AlertKind.class);

    private HeldAlerts(HeldAlertsFile file) {
        this.file = file;
    }

    /**
     * Opens the held alerts saved in {@code path}, if any. A file that cannot be
     * read is moved aside and the store starts empty.
     */
    public static HeldAlerts open(Path path) {
        HeldAlerts held = new HeldAlerts(new HeldAlertsFile(path));
        held.load();
        return held;
    }

    /** Holds {@code alert} and saves. */
    public void add(Alert alert) {
        Objects.requireNonNull(alert, "alert");
        synchronized (lock) {
            if (kept.size() < MAX_KEPT) {
                kept.add(alert);
            } else {
                overflow.merge(alert.kind(), 1, Integer::sum);
            }
            save();
        }
    }

    /** Whether nothing is held. */
    public boolean isEmpty() {
        synchronized (lock) {
            return kept.isEmpty() && overflow.isEmpty();
        }
    }

    /** Hands over everything held and empties the store, file included. */
    public Batch takeAll() {
        synchronized (lock) {
            Batch batch = new Batch(kept, overflow);
            kept.clear();
            overflow.clear();
            save();
            return batch;
        }
    }

    private void load() {
        synchronized (lock) {
            HeldAlertsFile.Contents contents = file.read();
            kept.addAll(contents.kept());
            overflow.putAll(contents.overflow());
        }
    }

    /** Call with {@link #lock} held. */
    private void save() {
        try {
            file.write(kept, overflow);
        } catch (IOException e) {
            log.warn("Could not save the alerts held for after quiet hours; they are kept until the host "
                    + "closes: {}", e.getMessage());
        }
    }

    /**
     * What was held, as handed over by {@link #takeAll()}.
     *
     * @param kept     the alerts kept, oldest first
     * @param overflow how many more of each kind were held than kept
     */
    public record Batch(List<Alert> kept, Map<AlertKind, Integer> overflow) {

        public Batch {
            kept = List.copyOf(kept);
            overflow = Map.copyOf(overflow);
        }

        /** How many alerts were held, kept or not. */
        public int total() {
            return kept.size() + overflowWhere(kind -> true);
        }

        /** The kept alerts whose kind {@code routed} accepts, oldest first. */
        public List<Alert> keptWhere(Predicate<AlertKind> routed) {
            return kept.stream().filter(alert -> routed.test(alert.kind())).toList();
        }

        /** How many alerts of the kinds {@code routed} accepts were held but not kept. */
        public int overflowWhere(Predicate<AlertKind> routed) {
            return overflow.entrySet().stream().filter(entry -> routed.test(entry.getKey()))
                    .mapToInt(Map.Entry::getValue).sum();
        }
    }
}
