package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.runtime.BoundedConstruction;
import com.botwithus.bot.core.runtime.SDNScriptLoader;
import com.botwithus.bot.core.runtime.SdnDelivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The Store scripts this host has been delivered since it started, held so every
 * client can have them, not just the clients connected at the moment of delivery.
 *
 * <p>A delivered jar is defined once, in memory, and never becomes a file. This
 * keeps a factory for each script it provided, so a client that connects later
 * and a reload of {@code scripts/} both get the script back without asking the
 * launcher again, and each client gets its own instance: a script object holds
 * per-run state, so one instance shared by two clients would share it.
 *
 * <p>Nothing here is persisted. What survives a restart is the
 * {@link InstalledScriptsLedger}'s record of which catalogue ids were installed;
 * {@link StoreRestorer} asks the launcher for those again. One shelf per host,
 * owned by the composition root; thread-safe.
 */
public final class StoreShelf {

    private static final Logger log = LoggerFactory.getLogger(StoreShelf.class);

    /**
     * One delivered script.
     *
     * @param catalogueId the catalogue entry it was delivered for; empty when the
     *                    delivery could not be paired with its request
     * @param factory     makes a new instance of it
     */
    private record Copy(Optional<String> catalogueId, Supplier<BotScript> factory) {}

    private final Object lock = new Object();
    /** Keyed by script class, in delivery order. Guarded by {@link #lock}. */
    private final Map<String, Copy> byClass = new LinkedHashMap<>();

    /** How long one new instance may take to construct. */
    private final Duration budget;

    /** An empty shelf; the host builds one and shares it. */
    public StoreShelf() {
        this(SDNScriptLoader.CONSTRUCT_BUDGET);
    }

    /** As {@link #StoreShelf()}, with the budget for each new instance; for tests. */
    public StoreShelf(Duration budget) {
        this.budget = budget;
    }

    /**
     * Shelves the scripts in {@code jar}, delivered for {@code catalogueId},
     * replacing any earlier delivery of the same class.
     *
     * @return the class names shelved
     */
    public List<String> stock(Optional<String> catalogueId, SdnDelivery.Jar jar) {
        List<String> classes = new ArrayList<>();
        synchronized (lock) {
            for (int i = 0; i < jar.scripts().size(); i++) {
                String cls = jar.scripts().get(i).getClass().getName();
                byClass.put(cls, new Copy(catalogueId, jar.copies().get(i)));
                classes.add(cls);
            }
        }
        return classes;
    }

    /** A new instance of every shelved script, in delivery order. */
    public List<BotScript> freshCopies() {
        return freshCopiesOf(classes());
    }

    /**
     * A new instance of each shelved script whose class is in {@code classes}. A
     * class not on the shelf is skipped, and so is one whose constructor throws,
     * which costs that script on this client and nothing else.
     */
    public List<BotScript> freshCopiesOf(Collection<String> classes) {
        List<Map.Entry<String, Copy>> wanted;
        synchronized (lock) {
            wanted = byClass.entrySet().stream().filter(e -> classes.contains(e.getKey()))
                    .map(e -> Map.entry(e.getKey(), e.getValue())).toList();
        }
        List<BotScript> copies = new ArrayList<>();
        for (Map.Entry<String, Copy> e : wanted) {
            copyOf(e.getKey(), e.getValue()).ifPresent(copies::add);
        }
        return copies;
    }

    /**
     * One new instance, made within the same budget as the first, so a constructor
     * that blocks costs that script on this client rather than the connect or reload
     * that asked for it.
     */
    private Optional<BotScript> copyOf(String cls, Copy copy) {
        return switch (BoundedConstruction.construct(cls, copy.factory(), budget)) {
            case BoundedConstruction.Outcome.Built<BotScript> built -> Optional.of(built.instance());
            case BoundedConstruction.Outcome.Threw<BotScript> threw -> {
                log.warn("SDN: could not make a new instance of Store script {}: {}", cls, threw.error().toString());
                yield Optional.empty();
            }
            case BoundedConstruction.Outcome.TimedOut<BotScript> timedOut -> {
                log.error("SDN: a new instance of Store script {} did not finish constructing within {} s; "
                        + "skipping it here", cls, timedOut.budget().toSeconds());
                yield Optional.empty();
            }
            case BoundedConstruction.Outcome.Interrupted<BotScript> ignored -> Optional.empty();
        };
    }

    /** The class names on the shelf. */
    public Set<String> classes() {
        synchronized (lock) {
            return Set.copyOf(byClass.keySet());
        }
    }

    /** The catalogue ids at least one shelved script was delivered for. */
    public Set<String> catalogueIds() {
        synchronized (lock) {
            return byClass.values().stream().flatMap(c -> c.catalogueId().stream()).collect(Collectors.toSet());
        }
    }
}
