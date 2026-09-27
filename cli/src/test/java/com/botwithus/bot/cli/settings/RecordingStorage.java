package com.botwithus.bot.cli.settings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The real {@link PropertiesFileStorage}, wrapped to count saves, let a test wait
 * for one, and make saves fail on demand.
 */
final class RecordingStorage implements SettingsStorage {

    private static final long SAVE_WAIT_SECONDS = 5L;

    private final PropertiesFileStorage file;
    private final AtomicInteger saves = new AtomicInteger();
    private final Semaphore saved = new Semaphore(0);
    private final AtomicBoolean isFailing = new AtomicBoolean();

    RecordingStorage(Path file) {
        this.file = new PropertiesFileStorage(file);
    }

    @Override
    public Map<String, String> load() throws IOException {
        return file.load();
    }

    @Override
    public void save(Map<String, String> entries) throws IOException {
        try {
            if (isFailing.get()) {
                throw new IOException("disk says no");
            }
            file.save(entries);
            saves.incrementAndGet();
        } finally {
            saved.release();
        }
    }

    @Override
    public Path location() {
        return file.location();
    }

    int saves() {
        return saves.get();
    }

    void failSaves(boolean fail) {
        isFailing.set(fail);
    }

    /** Waits for one save attempt (successful or not) to finish. */
    boolean awaitSaveAttempt() throws InterruptedException {
        return saved.tryAcquire(SAVE_WAIT_SECONDS, TimeUnit.SECONDS);
    }
}
