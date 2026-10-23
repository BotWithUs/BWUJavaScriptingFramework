package com.botwithus.bot.cli.launcher;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.core.launcher.ClientLauncherImpl;
import com.botwithus.bot.core.launcher.CloseRequest;
import com.botwithus.bot.core.launcher.DevGate;
import com.botwithus.bot.core.launcher.HelloParams;
import com.botwithus.bot.core.launcher.LauncherService;
import com.botwithus.bot.core.launcher.PipeFrameChannel;
import com.botwithus.bot.core.launcher.ServiceLocation;
import com.botwithus.bot.core.launcher.ServiceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * This host's registration with the BotWithUs launcher service (launcher ADR
 * 0007, sections 6.1 and 10.1). The composition root opens it once at start-up,
 * before any script runs, and closes it at shutdown.
 *
 * <p>Registering is what makes a data update wait while this host is open, so
 * it happens whether or not any management script ever launches a client. The
 * connection reconnects by itself for as long as the host runs. <b>The host
 * never starts the service</b>; without one, launcher calls fail with
 * {@code service_unavailable}, or {@code service_stopped} when the user stopped
 * it from the tray.</p>
 */
public final class LauncherHost implements AutoCloseable {

    /** {@code hello}'s label for the windowed host. */
    public static final String GUI_LABEL = "BotWithUs Java host";
    /** {@code hello}'s label for the headless command-line host. */
    public static final String CLI_LABEL = "BotWithUs Java host (CLI)";
    /** How long an attach after a launch keeps trying for the agent's pipe and shared memory. */
    static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(15);

    private static final Logger log = LoggerFactory.getLogger(LauncherHost.class);
    private static final String DEV_VERSION = "dev";

    private final LauncherService service;
    private final ClientLauncherImpl launcher;

    private LauncherHost(LauncherService service, ClientLauncherImpl launcher) {
        this.service = service;
        this.launcher = launcher;
    }

    /**
     * Registers this process with the service and gives {@code ctx} the
     * launcher its management scripts use.
     *
     * @param ctx           the host's context; its attach is the launcher's attach callback
     * @param label         {@link #GUI_LABEL} or {@link #CLI_LABEL}
     * @param gate          whether development overrides apply
     * @param closeRequests receives the service's close requests; must not block
     * @return the registration, or empty when this process cannot name the
     *         service's pipe (its token or session could not be read)
     */
    public static Optional<LauncherHost> register(CliContext ctx, String label, DevGate gate,
                                                  Consumer<CloseRequest> closeRequests) {
        Objects.requireNonNull(ctx, "ctx");
        ServiceLocation location;
        try {
            location = ServiceLocation.resolve(ServiceScope.current(), gate, System::getenv);
        } catch (RuntimeException e) {
            log.error("Cannot work out the launcher service's pipe name; client launching is unavailable", e);
            return Optional.empty();
        }
        HelloParams hello = new HelloParams(version(), ProcessHandle.current().pid(), Optional.of(label));
        LauncherService service = new LauncherService(PipeFrameChannel.opener(location.pipeName()), hello,
                location::isUserStopped, LauncherService.Timings.DEFAULT, closeRequests);
        ClientLauncherImpl launcher = new ClientLauncherImpl(service,
                pid -> ctx.attachLaunchedClient(pid, ATTACH_TIMEOUT));
        ctx.setClientLauncher(launcher);
        service.start();
        log.info("Registering with the launcher service on pipe {}", location.pipeName());
        return Optional.of(new LauncherHost(service, launcher));
    }

    /** @return the service connection, for answering close requests */
    public LauncherService service() {
        return service;
    }

    @Override
    public void close() {
        launcher.close();
        service.close();
    }

    private static String version() {
        String version = LauncherHost.class.getPackage().getImplementationVersion();
        return version != null ? version : DEV_VERSION;
    }
}
