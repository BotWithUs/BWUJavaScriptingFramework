package com.botwithus.bot.cli.sdnlive;

import com.botwithus.bot.cli.gui.window.LwjglMemoryBackend;
import com.botwithus.bot.core.crypto.SdnDiskBundleSource;
import com.botwithus.bot.core.crypto.SdnLoader;
import com.botwithus.bot.core.launcher.DevGate;
import com.botwithus.bot.core.launcher.HelloParams;
import com.botwithus.bot.core.launcher.LauncherService;
import com.botwithus.bot.core.launcher.PipeFrameChannel;
import com.botwithus.bot.core.launcher.ServiceLocation;
import com.botwithus.bot.core.launcher.ServiceScope;
import com.botwithus.bot.core.sdn.SdnInstaller;
import com.botwithus.bot.core.sdn.SdnPipeRoute;
import com.botwithus.bot.core.sdn.SdnRendezvous;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * The live check of Store installs over the launcher's automation pipe
 * (amendment B1 of the launcher's ADR 0007), through the same two screens
 * {@link SdnLiveCheck} drives: the Store page and the "Start a script" picker.
 *
 * <p>Unlike {@link SdnLiveCheck} there is no stand-in: the bundle comes from a
 * real launcher service, sealed through its real link, and is defined by the
 * runtime that service launched this process on. That is the point, and it is
 * why this check cannot be started from Gradle: a launcher of that version
 * delivers only to a host it started itself. Register this class as a scripting
 * host and launch it through the service, with these system properties:
 *
 * <ul>
 *   <li>{@code botwithus.sdnpipe.mode}: {@code store} or {@code picker}, for a
 *       host the service started; {@code refused}, for one it did not, which must
 *       be told {@code sdn_not_delivered} and install nothing</li>
 *   <li>{@code botwithus.sdnlive.pid}: the game client the picker starts the
 *       script on</li>
 *   <li>{@code botwithus.sdnpipe.id}: optional; the catalogue entry to install.
 *       Without it, the first entry the account may install is used.</li>
 * </ul>
 *
 * <p>Exit status: 0 pass, 1 fail, 2 could not run.
 */
public final class SdnPipeLiveCheck {

    private static final Logger log = LoggerFactory.getLogger(SdnPipeLiveCheck.class);

    private static final String PREFIX = "botwithus.sdnpipe.";
    private static final String PID_PROPERTY = "botwithus.sdnlive.pid";
    private static final String HOST_LABEL = "SDN pipe live check";
    private static final String CLIENT_VERSION = "sdn-live";
    private static final Duration REGISTER_WAIT = Duration.ofSeconds(20);
    private static final int EXIT_PASS = 0;
    private static final int EXIT_FAIL = 1;
    private static final int EXIT_UNUSABLE = 2;

    /** Which side of the launcher's ownership rule this process is on. */
    enum Mode {
        STORE, PICKER, REFUSED
    }

    private SdnPipeLiveCheck() {
    }

    public static void main(String[] args) {
        // First, before anything initialises LWJGL's MemoryUtil: see LwjglMemoryBackend.
        LwjglMemoryBackend.selectFfm();
        // Exits explicitly: a connected host leaves non-daemon threads behind.
        System.exit(runReportingStatus());
    }

    private static int runReportingStatus() {
        try {
            Mode mode = Mode.valueOf(required(PREFIX + "mode").toUpperCase(Locale.ROOT));
            long pid = Long.parseLong(required(PID_PROPERTY));
            return run(mode, pid, Optional.ofNullable(System.getProperty(PREFIX + "id"))) ? EXIT_PASS : EXIT_FAIL;
        } catch (IllegalArgumentException e) {
            log.error("SDN-LIVE cannot run: {}", e.getMessage());
            return EXIT_UNUSABLE;
        } catch (RuntimeException e) {
            log.error("SDN-LIVE FAIL the run broke", e);
            return EXIT_FAIL;
        }
    }

    private static boolean run(Mode mode, long pid, Optional<String> chosenId) {
        Verdict verdict = new Verdict();
        log.info("SDN-LIVE pipe check, mode {}, client {}, this host pid {}", mode, pid,
                ProcessHandle.current().pid());
        verdict.expect("the runtime can define a Store bundle", SdnLoader.isAvailable(),
                () -> "this JVM has no SDN class loader: " + System.getProperty("java.home"));
        verdict.expect("the host was started with delivery on", SdnDiskBundleSource.isEnabled(),
                () -> "-D" + SdnDiskBundleSource.ENABLE_PROP + " is not true");
        try (LauncherService service = openLauncher();
             RendezvousWatch watch = RendezvousWatch.start(SdnRendezvous.directory());
             LiveHost host = LiveHost.connect(pid)) {
            boolean registered = registered(service, verdict);
            if (registered) {
                Screens screens = new Screens(host, new SdnInstaller(SdnPipeRoute.over(() -> Optional.of(service))));
                new PipeScenarios(screens, host, verdict).run(mode, chosenId);
            }
            verdict.expect("no install file was written to " + SdnRendezvous.directory(),
                    watch.installFilesSeen().isEmpty(), () -> "seen " + watch.installFilesSeen());
        }
        verdict.summarise();
        return verdict.passed();
    }

    private static boolean registered(LauncherService service, Verdict verdict) {
        boolean isRegistered = Waits.until(service::isConnected, REGISTER_WAIT);
        verdict.expect("registered on the launcher's automation pipe", isRegistered,
                () -> "no registration within " + REGISTER_WAIT);
        if (isRegistered) {
            verdict.expect("the launcher lists sdn.bundle in hello", service.offersSdnBundles(),
                    () -> "hello did not list it: an older launcher");
        }
        return isRegistered;
    }

    private static LauncherService openLauncher() {
        ServiceLocation location = ServiceLocation.resolve(ServiceScope.current(), DevGate.fromSystemProperties(),
                System::getenv);
        log.info("SDN-LIVE launcher pipe {}", location.pipeName());
        LauncherService service = new LauncherService(PipeFrameChannel.opener(location.pipeName()),
                new HelloParams(CLIENT_VERSION, ProcessHandle.current().pid(), Optional.of(HOST_LABEL)),
                location::isUserStopped, LauncherService.Timings.DEFAULT, request -> { });
        service.start();
        return service;
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("-D" + key + " is required");
        }
        return value;
    }
}
