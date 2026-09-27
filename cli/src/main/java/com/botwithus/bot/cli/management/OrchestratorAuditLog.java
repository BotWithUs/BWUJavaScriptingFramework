package com.botwithus.bot.cli.management;

import com.botwithus.bot.cli.events.HostEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * What each management script has done through its orchestrator: the recent
 * calls that start, stop, schedule or change something, including the ones its
 * targets refused. Kept per script, newest last, up to {@link #CAPACITY}; older
 * entries drop off. Each entry is also published as a
 * {@link HostEvent.ManagementAction}.
 *
 * <p>Queries (lists, statuses) are not recorded: a script polls them every
 * loop, and they would push every action out of the log within seconds.</p>
 *
 * <p>Thread-safe: every management script records from its own thread.</p>
 */
public final class OrchestratorAuditLog {

    /** How many entries are kept per script. */
    public static final int CAPACITY = 200;

    /**
     * One orchestrator call.
     *
     * @param at     when it was made
     * @param call   the orchestrator method, e.g. {@code startScript}
     * @param target what it named, as shown to the user
     * @param result how it went, as shown to the user
     */
    public record Entry(Instant at, String call, String target, String result) {
        public Entry {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(result, "result");
        }
    }

    private final Consumer<? super HostEvent> publisher;
    private final Clock clock;
    private final Map<String, Deque<Entry>> rings = new ConcurrentHashMap<>();

    /**
     * @param publisher where each entry is published, as a management action
     * @param clock     stamps the entries
     */
    public OrchestratorAuditLog(Consumer<? super HostEvent> publisher, Clock clock) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Records a call {@code script} made, and publishes it. */
    public void record(String script, String call, String target, String result) {
        Entry entry = new Entry(clock.instant(), call, target, result);
        Deque<Entry> ring = rings.computeIfAbsent(script, name -> new ArrayDeque<>());
        synchronized (ring) {
            if (ring.size() == CAPACITY) {
                ring.removeFirst();
            }
            ring.addLast(entry);
        }
        publisher.accept(new HostEvent.ManagementAction(script, call, target, result, entry.at()));
    }

    /** The calls {@code script} made, oldest first; empty if it made none. */
    public List<Entry> entries(String script) {
        Deque<Entry> ring = rings.get(script);
        if (ring == null) {
            return List.of();
        }
        synchronized (ring) {
            return List.copyOf(ring);
        }
    }
}
