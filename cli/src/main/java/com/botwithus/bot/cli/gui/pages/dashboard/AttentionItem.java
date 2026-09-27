package com.botwithus.bot.cli.gui.pages.dashboard;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Liveness;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One entry of "Needs attention": something that stopped against the user's
 * will or is about to. Each variant is a present condition, not a past event,
 * so an entry leaves the list as soon as the condition clears.
 */
public sealed interface AttentionItem {

    /** How loud the entry is. */
    enum Severity {
        /** Stopped against the user's will. Listed first. */
        ERROR,
        /** Needs a look soon. */
        WARNING
    }

    /** Stable across frames while the condition lasts; keys the entry's expanded state. */
    String key();

    Severity severity();

    /** When the condition started, if the host saw it start. */
    Optional<Instant> since();

    /** The client this is about, for scoping; empty for host-wide entries. */
    Optional<String> client();

    /** A script sits inside one {@code onLoop()} past the watchdog's threshold. */
    record Stalled(RunnerRef ref, String clientLabel, Optional<Instant> since, long iteration)
            implements AttentionItem {
        public Stalled {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(since, "since");
        }

        @Override
        public String key() {
            return "stall:" + ref.client() + ":" + ref.script();
        }

        @Override
        public Severity severity() {
            return Severity.WARNING;
        }

        @Override
        public Optional<String> client() {
            return Optional.of(ref.client());
        }
    }

    /**
     * A script's current run threw and ended.
     *
     * @param total every crash this runner has had
     * @param trace the stack trace, rendered once
     */
    record Crashed(RunnerRef ref, String clientLabel, LastCrash crash, long total, String trace)
            implements AttentionItem {
        public Crashed {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(crash, "crash");
            Objects.requireNonNull(trace, "trace");
        }

        @Override
        public String key() {
            return "crash:" + ref.client() + ":" + ref.script();
        }

        @Override
        public Severity severity() {
            return Severity.ERROR;
        }

        @Override
        public Optional<Instant> since() {
            return Optional.of(crash.when());
        }

        @Override
        public Optional<String> client() {
            return Optional.of(ref.client());
        }
    }

    /** A script ignored a stop and was cut off; it cannot start again until the host restarts. */
    record CutOff(RunnerRef ref, String clientLabel, Liveness liveness) implements AttentionItem {
        public CutOff {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(liveness, "liveness");
        }

        @Override
        public String key() {
            return "cutoff:" + ref.client() + ":" + ref.script();
        }

        @Override
        public Severity severity() {
            return Severity.ERROR;
        }

        @Override
        public Optional<Instant> since() {
            return Optional.empty();
        }

        @Override
        public Optional<String> client() {
            return Optional.of(ref.client());
        }
    }

    /**
     * A client's pipe dropped and the host is retrying.
     *
     * @param attempt     the retry in flight or next, 1-based; 0 before the first
     * @param nextDelayMs how long until that retry, as the reconnect loop reported it
     */
    record NotResponding(String pipe, String clientLabel, int attempt, long nextDelayMs,
                         Optional<Instant> since) implements AttentionItem {
        public NotResponding {
            Objects.requireNonNull(pipe, "pipe");
            Objects.requireNonNull(since, "since");
        }

        @Override
        public String key() {
            return "conn:" + pipe;
        }

        @Override
        public Severity severity() {
            return Severity.WARNING;
        }

        @Override
        public Optional<String> client() {
            return Optional.of(pipe);
        }
    }

    /** A client's reconnect loop ran out of attempts. */
    record GaveUp(String pipe, String clientLabel, int attempts, Optional<Instant> since)
            implements AttentionItem {
        public GaveUp {
            Objects.requireNonNull(pipe, "pipe");
            Objects.requireNonNull(since, "since");
        }

        @Override
        public String key() {
            return "gaveup:" + pipe;
        }

        @Override
        public Severity severity() {
            return Severity.ERROR;
        }

        @Override
        public Optional<String> client() {
            return Optional.of(pipe);
        }
    }

    /**
     * A script JAR failed in the latest load pass.
     *
     * @param error the cause's type and message on one line
     * @param trace the cause's stack trace, rendered once
     */
    record LoadFailed(Path jar, String error, String trace, Optional<Instant> since)
            implements AttentionItem {
        public LoadFailed {
            Objects.requireNonNull(jar, "jar");
            Objects.requireNonNull(error, "error");
            Objects.requireNonNull(trace, "trace");
            Objects.requireNonNull(since, "since");
        }

        @Override
        public String key() {
            return "jar:" + jar;
        }

        @Override
        public Severity severity() {
            return Severity.ERROR;
        }

        @Override
        public Optional<String> client() {
            return Optional.empty();
        }
    }
}
