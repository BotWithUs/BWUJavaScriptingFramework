package com.botwithus.bot.cli;

import com.botwithus.bot.core.impl.GameAPIImpl;
import com.botwithus.bot.core.runtime.ScriptUiLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The plugin-UI lifecycle of management scripts, fanned out over every connection.
 *
 * <p>A management script can draw on any client, so each run is opened and released
 * on all of them. The run ids are this host's, unique across management runs; each
 * run is also its own owner name ({@code ManagementCallerTag.ownerOf(name, run)}), so
 * a connection added mid-run, which never saw the run open, still cannot let an old
 * run's release reach a newer run there.</p>
 */
final class ManagementGameUiFanOut {

    private static final Logger log = LoggerFactory.getLogger(ManagementGameUiFanOut.class);

    private final Supplier<List<GameAPIImpl>> connections;
    private final AtomicLong runs = new AtomicLong();

    /** @param connections every live connection's {@code GameAPIImpl}, read at each call */
    ManagementGameUiFanOut(Supplier<List<GameAPIImpl>> connections) {
        this.connections = connections;
    }

    ScriptUiLifecycle lifecycle() {
        return new ScriptUiLifecycle(runs::incrementAndGet,
                (owner, run) -> each(api -> api.openGameUi(owner, run)),
                (owner, run) -> each(api -> api.releaseGameUi(owner, run)));
    }

    /** Runs {@code action} on every connection; one that throws does not stop the rest. */
    private void each(Consumer<GameAPIImpl> action) {
        for (GameAPIImpl api : connections.get()) {
            try {
                action.accept(api);
            } catch (RuntimeException e) {
                log.debug("Plugin UI hook on a connection failed: {}", e.getMessage());
            }
        }
    }
}
