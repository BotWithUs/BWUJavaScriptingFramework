package com.botwithus.bot.cli.management;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts the management scripts the host should keep running, once the first
 * load pass after the host starts has registered them. Later passes start
 * nothing here: what runs after a reload is the reload's own choice.
 *
 * <p>Thread-safe; exactly one caller of {@link #afterLoadPass} starts the scripts.</p>
 */
public final class ManagementStartup {

    private static final Logger log = LoggerFactory.getLogger(ManagementStartup.class);

    private final ManagementControl control;
    private final AtomicBoolean hasLoaded = new AtomicBoolean();

    public ManagementStartup(ManagementControl control) {
        this.control = Objects.requireNonNull(control, "control");
    }

    /**
     * Call after each load pass has registered its scripts. After the first,
     * starts each loaded script that should be running.
     *
     * @return the scripts started; empty after every pass but the first
     */
    public List<String> afterLoadPass() {
        if (!hasLoaded.compareAndSet(false, true)) {
            return List.of();
        }
        List<String> started = control.startWanted();
        if (!started.isEmpty()) {
            log.info("Started {} management script(s) that were running before: {}", started.size(), started);
        }
        return started;
    }

    /** Whether a load pass has finished since the host started. */
    public boolean hasLoaded() {
        return hasLoaded.get();
    }
}
