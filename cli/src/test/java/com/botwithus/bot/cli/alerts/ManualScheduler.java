package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * An {@link AlertScheduler} a test cranks by hand: {@link #advance} moves the
 * clock and runs every task that has come due, in time order, on the test thread.
 */
final class ManualScheduler implements AlertScheduler {

    private static final class Task {
        private final Instant at;
        private final Runnable body;
        private boolean isCancelled;

        private Task(Instant at, Runnable body) {
            this.at = at;
            this.body = body;
        }
    }

    private final List<Task> tasks = new ArrayList<>();
    private final ManualClock clock;

    ManualScheduler(ManualClock clock) {
        this.clock = clock;
    }

    @Override
    public Scheduled schedule(Instant at, Runnable body) {
        Task task = new Task(at, body);
        tasks.add(task);
        return () -> task.isCancelled = true;
    }

    /** Moves the clock forward by {@code by}, running each task as its moment passes. */
    void advance(Duration by) {
        Instant target = clock.instant().plus(by);
        while (true) {
            Task next = tasks.stream()
                    .filter(task -> !task.isCancelled && !task.at.isAfter(target))
                    .min(Comparator.comparing(task -> task.at))
                    .orElse(null);
            if (next == null) {
                break;
            }
            tasks.remove(next);
            if (next.at.isAfter(clock.instant())) {
                clock.set(next.at);
            }
            next.body.run();
        }
        clock.set(target);
    }

    /** When the pending (not cancelled) tasks are due, earliest first. */
    List<Instant> pending() {
        return tasks.stream().filter(task -> !task.isCancelled).map(task -> task.at).sorted().toList();
    }
}
