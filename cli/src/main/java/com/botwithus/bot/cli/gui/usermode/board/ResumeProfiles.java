package com.botwithus.bot.cli.gui.usermode.board;

import com.botwithus.bot.core.config.ScriptProfileStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * What each account's script profile says, for the cards: whether it resumes
 * after a restart, and which scripts it would resume. The profile store reads
 * and writes files, so this answers the render thread from a cache and does
 * every read and write on the I/O executor. A cached profile is read again once
 * it is {@link #REFRESH} old, which is how a profile saved by the host as
 * scripts start and stop reaches a card.
 */
final class ResumeProfiles {

    private static final Logger log = LoggerFactory.getLogger(ResumeProfiles.class);

    /** How old a cached profile may get before it is read again. */
    static final Duration REFRESH = Duration.ofSeconds(5);
    /** The store's own default for an account it has no profile for. */
    private static final boolean DEFAULT_AUTO_START = true;

    /**
     * An account's profile as the cards see it.
     *
     * @param autoStart the "resume after restart" setting; empty when the host keeps no profiles
     * @param scripts   the scripts it would resume, in the order they were saved
     */
    record Profile(Optional<Boolean> autoStart, List<String> scripts) {
        static final Profile NONE = new Profile(Optional.empty(), List.of());
        static final Profile UNREAD = new Profile(Optional.of(DEFAULT_AUTO_START), List.of());
    }

    private record Cached(Profile profile, Instant readAt) { }

    private final Supplier<ScriptProfileStore> store;
    private final Executor io;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Set<String> reading = ConcurrentHashMap.newKeySet();
    private final Set<String> writing = ConcurrentHashMap.newKeySet();

    /**
     * @param store the host's profile store; may answer {@code null} when the host keeps none
     * @param io    runs the store's reads and writes
     */
    ResumeProfiles(Supplier<ScriptProfileStore> store, Executor io, Clock clock) {
        this.store = store;
        this.io = io;
        this.clock = clock;
    }

    /**
     * The account's profile as last read, or the store's defaults until the first
     * read lands. Queues a read when the cached one is missing or stale.
     */
    Profile of(String accountUuid) {
        if (store.get() == null) {
            return Profile.NONE;
        }
        Cached cached = cache.get(accountUuid);
        if (cached == null || !clock.instant().isBefore(cached.readAt().plus(REFRESH))) {
            requestRead(accountUuid);
        }
        return cached != null ? cached.profile() : Profile.UNREAD;
    }

    /**
     * Turns resuming on or off for the account. The cards show the new value at
     * once; the store is written on the I/O executor.
     */
    void setAutoStart(String accountUuid, boolean isOn) {
        ScriptProfileStore profiles = store.get();
        if (profiles == null) {
            return;
        }
        List<String> scripts = Optional.ofNullable(cache.get(accountUuid))
                .map(cached -> cached.profile().scripts())
                .orElse(List.of());
        cache.put(accountUuid, new Cached(new Profile(Optional.of(isOn), scripts), clock.instant()));
        // A read already queued would otherwise land after this and put the old
        // value back until the next refresh; while the write is pending, reads
        // leave the cache alone, and the write reads the profile back itself.
        writing.add(accountUuid);
        boolean isQueued = submit(() -> {
            try {
                profiles.setAutoStart(accountUuid, isOn);
            } finally {
                writing.remove(accountUuid);
            }
            read(accountUuid);
        });
        if (!isQueued) {
            writing.remove(accountUuid);
        }
    }

    private void requestRead(String accountUuid) {
        if (!reading.add(accountUuid)) {
            return;
        }
        boolean isQueued = submit(() -> {
            try {
                read(accountUuid);
            } finally {
                reading.remove(accountUuid);
            }
        });
        if (!isQueued) {
            reading.remove(accountUuid);
        }
    }

    private void read(String accountUuid) {
        ScriptProfileStore profiles = store.get();
        if (profiles == null) {
            return;
        }
        Profile profile = new Profile(Optional.of(profiles.isAutoStart(accountUuid)),
                List.copyOf(profiles.getAccountScripts(accountUuid)));
        if (!writing.contains(accountUuid)) {
            cache.put(accountUuid, new Cached(profile, clock.instant()));
        }
    }

    private boolean submit(Runnable task) {
        try {
            io.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            log.debug("Script profile work not queued: {}", e.toString());
            return false;
        }
    }
}
