package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.script.ScriptManager;
import com.botwithus.bot.api.script.ScriptScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Scheduler implementation backed by a single-thread {@link ScheduledExecutorService}.
 * Each schedule entry gets a virtual-thread task that delegates to {@link ScriptManager}.
 */
public class ScriptSchedulerImpl implements ScriptScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScriptSchedulerImpl.class);
    private final ScriptManager manager;
    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<String, ScheduleState> schedules = new ConcurrentHashMap<>();

    private record ScheduleState(
            String scriptName,
            Instant nextRun,
            Duration interval,
            Duration maxDuration,
            ScheduledFuture<?> future,
            ScheduledFuture<?> stopFuture
    ) {}

    public ScriptSchedulerImpl(ScriptManager manager) {
        this.manager = manager;
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = Thread.ofPlatform().daemon().name("script-scheduler").unstarted(r);
            return t;
        });
        pool.setRemoveOnCancelPolicy(true);
        this.executor = pool;
    }

    @Override
    public String runAfter(String scriptName, Duration delay) {
        return runAfter(scriptName, delay, null);
    }

    @Override
    public String runAfter(String scriptName, Duration delay, Map<String, Object> config) {
        String id = newId();
        Instant nextRun = Instant.now().plus(delay);
        ScheduledFuture<?> future = executor.schedule(() -> {
            if (config != null) {
                manager.start(scriptName, config);
            } else {
                manager.start(scriptName);
            }
            schedules.remove(id);
        }, delay.toMillis(), TimeUnit.MILLISECONDS);

        schedules.put(id, new ScheduleState(scriptName, nextRun, null, null, future, null));
        log.info("Scheduled '{}' to run after {} (id={})", scriptName, delay, id);
        return id;
    }

    @Override
    public String runAt(String scriptName, Instant at) {
        Duration delay = Duration.between(Instant.now(), at);
        if (delay.isNegative()) {
            delay = Duration.ZERO;
        }
        return runAfter(scriptName, delay);
    }

    @Override
    public String runEvery(String scriptName, Duration interval) {
        return runEvery(scriptName, interval, null);
    }

    @Override
    public String runEvery(String scriptName, Duration interval, Duration maxDuration) {
        String id = newId();
        Instant nextRun = Instant.now().plus(interval);

        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
            try {
                if (manager.isRunning(scriptName)) {
                    manager.restart(scriptName);
                } else {
                    manager.start(scriptName);
                }

                // Schedule auto-stop if maxDuration is set
                if (maxDuration != null) {
                    ScheduledFuture<?> stopFuture = executor.schedule(() -> {
                        if (manager.isRunning(scriptName)) {
                            manager.stop(scriptName);
                            log.info("Auto-stopped '{}' after {} (id={})", scriptName, maxDuration, id);
                        }
                    }, maxDuration.toMillis(), TimeUnit.MILLISECONDS);

                    // Update state with stop future
                    ScheduleState old = schedules.get(id);
                    if (old != null) {
                        schedules.put(id, new ScheduleState(
                                old.scriptName, Instant.now().plus(interval),
                                old.interval, old.maxDuration, old.future, stopFuture));
                    }
                }
            } catch (Exception e) {
                log.error("Error running '{}': {}", scriptName, e.getMessage());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);

        schedules.put(id, new ScheduleState(scriptName, nextRun, interval, maxDuration, future, null));
        if (maxDuration != null) {
            log.info("Scheduled '{}' every {} (max {} per run) (id={})", scriptName, interval, maxDuration, id);
        } else {
            log.info("Scheduled '{}' every {} (id={})", scriptName, interval, id);
        }
        return id;
    }

    @Override
    public boolean cancel(String scheduleId) {
        ScheduleState state = schedules.remove(scheduleId);
        if (state == null) return false;

        state.future.cancel(false);
        if (state.stopFuture != null) {
            state.stopFuture.cancel(false);
        }
        log.info("Cancelled schedule for '{}' (id={})", state.scriptName, scheduleId);
        return true;
    }

    @Override
    public void cancelAll() {
        for (String id : List.copyOf(schedules.keySet())) {
            cancel(id);
        }
    }

    @Override
    public List<ScheduledEntry> listScheduled() {
        return schedules.entrySet().stream()
                .map(e -> new ScheduledEntry(
                        e.getKey(),
                        e.getValue().scriptName,
                        e.getValue().nextRun,
                        e.getValue().interval,
                        e.getValue().maxDuration))
                .toList();
    }

    /**
     * Shuts down the scheduler. Called when the runtime is closing.
     */
    public void shutdown() {
        cancelAll();
        executor.shutdownNow();
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
