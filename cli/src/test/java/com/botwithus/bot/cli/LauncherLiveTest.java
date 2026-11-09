package com.botwithus.bot.cli;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.botwithus.bot.api.script.ClientLauncher;
import com.botwithus.bot.api.script.LaunchHandle;
import com.botwithus.bot.api.script.LaunchOutcome;
import com.botwithus.bot.api.script.LaunchedClient;
import com.botwithus.bot.api.script.LauncherAccount;
import com.botwithus.bot.api.script.LauncherEvent;
import com.botwithus.bot.api.script.LauncherException;
import com.botwithus.bot.api.script.ManagementContext;
import com.botwithus.bot.api.script.ManagementScript;
import com.botwithus.bot.api.script.StopMode;
import com.botwithus.bot.cli.groups.GroupsFile;
import com.botwithus.bot.cli.launcher.LauncherHost;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;
import com.botwithus.bot.cli.management.OrchestratorAuditLog;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.core.config.ScriptProfileStore;
import com.botwithus.bot.core.launcher.CloseRequest;
import com.botwithus.bot.core.launcher.DevGate;
import com.botwithus.bot.core.pipe.AgentPin;
import com.botwithus.bot.core.pipe.PipeClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The launcher acceptance checks against a real Debug {@code bwu_service}
 * (launcher ADR 0007, section 12, the Java A6 rows). Opt-in:
 * {@code :cli:launcherLiveTest}, with {@code botwithus.live.launcher=true}. The
 * host side is the production code end to end: a real {@link CliContext}, its
 * {@link LauncherHost} registration, real management scripts run by the host's
 * management runtime (so each gets its launcher from the production context
 * factory, scoped by its real targets), and real agent pipes.
 *
 * <p>Every host store lives in a temporary directory; nothing here writes the
 * user's host configuration. Properties:</p>
 * <ul>
 *   <li>{@code botwithus.live.account}: the account to launch on (Y in the scope check);</li>
 *   <li>{@code botwithus.live.serviceExe}: the Debug {@code bwu_service.exe} this run
 *       may kill and start again (the restart check only).</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "botwithus.live.launcher", matches = "true")
class LauncherLiveTest {

    private static final Duration REGISTER_WITHIN = Duration.ofSeconds(30);
    private static final Duration LAUNCH_WITHIN = Duration.ofMinutes(5);
    private static final Duration LOBBY_WITHIN = Duration.ofMinutes(4);
    private static final Duration EXIT_WITHIN = Duration.ofMinutes(2);
    private static final Duration SERVICE_DOWN_FOR = Duration.ofSeconds(20);
    private static final long FAST_SCAN_MS = 500L;
    /** An account id no service has: the scoped script's only target. */
    private static final String NOBODYS_ACCOUNT = "00000000000000000000000000000a6a";

    @TempDir
    Path tempDir;

    private CliContext ctx;
    private LauncherHost host;
    private AutoStartManager autoStart;
    /** The clients this test launched; auto-connect may reach these and nothing else. */
    private final Set<String> launchedHere = ConcurrentHashMap.newKeySet();
    private volatile ClientLauncher ownLauncher;
    /** Lookups the pinned scanner could not make; any at all means the race went untested. */
    private final AtomicInteger scannerFailures = new AtomicInteger();
    /**
     * Times the pinned scanner offered a client this test launched while that
     * client was still unattached (its pipe not yet among the host's
     * connections). Must grow during every launch. It proves a scan offered the
     * pid inside the window before the attach, not that auto-connect then tried
     * to connect: the manager also stands aside while the launcher is mid-attach.
     */
    private final AtomicInteger scannerOfferedUnattached = new AtomicInteger();
    private final List<CloseRequest> closeRequests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void register() throws InterruptedException {
        PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        LogBuffer logBuffer = new LogBuffer();
        ctx = new CliContext(logBuffer, new LogCapture(logBuffer, discard, discard),
                tempDir.resolve(GroupsFile.FILE_NAME));
        HostSettings settings = HostSettings.openForHost(tempDir.resolve("settings"));
        ctx.setSettings(settings);
        host = LauncherHost.register(ctx, LauncherHost.CLI_LABEL + " (A6 live test)", DevGate.fromSystemProperties(),
                closeRequests::add).orElseThrow();
        awaitTrue("registration", REGISTER_WITHIN, () -> host.service().isConnected());
        ctx.initManagementRuntime();
    }

    @AfterEach
    void close() {
        if (autoStart != null) {
            autoStart.stop();
        }
        if (ctx.getManagementRuntime() != null) {
            ctx.getManagementRuntime().stopAll();
        }
        ctx.disconnectAll();
        host.close();
    }

    /**
     * Acceptance 1 and 2: a management script launches a client, the host
     * attaches when it is injected, the client reaches the lobby, and it is
     * stopped, once each way. Auto-connect is on and scanning fast the whole
     * time, and no two pipe clients are ever open to the launched game.
     */
    @Test
    void managerScript_launchesAttachesReachesLobbyAndStopsEachWay() throws Exception {
        PipeOpens opens = PipeOpens.watch();
        startAutoConnect();
        LiveWholeHostManager script = new LiveWholeHostManager();
        ClientLauncher launcher = start(script, new Target.Host());
        String account = liveAccount(launcher);
        stopExistingClients(launcher, account);
        for (StopMode mode : StopMode.values()) {
            int offeredBefore = scannerOfferedUnattached.get();
            LaunchedClient client = launchAndReachLobby(launcher, account);
            assertTrue(scannerOfferedUnattached.get() > offeredBefore, "auto-connect's scanner never offered launched "
                    + "client " + client.clientId() + " (" + mode + ") while it was still unattached, so auto-connect "
                    + "had no window to race the attach and the no-two-pipe-clients check below would prove nothing");
            launcher.stop(client.clientId(), mode);
            awaitTrue("client.exited for " + client.clientId(), EXIT_WITHIN,
                    () -> script.exited(client.clientId()).isPresent());
            awaitTrue("pid " + client.pid() + " to be gone", EXIT_WITHIN,
                    () -> ProcessHandle.of(client.pid()).map(p -> !p.isAlive()).orElse(true));
            System.out.printf("LIVE %s: client %s pid %d exited %s; events %s%n", mode, client.clientId(),
                    client.pid(), script.exited(client.clientId()).orElseThrow(),
                    script.kindsFor(client.clientId()));
            assertTrue(script.kindsFor(client.clientId()).stream().anyMatch(k -> k.startsWith("ClientExited")),
                    "no client.exited for " + client.clientId());
            assertTrue(opens.maxOpenAtOnce(client.pid()) <= 1, "two pipe clients at once for pid " + client.pid()
                    + ": " + opens.log(client.pid()));
            assertTrue(opens.opensFor(client.pid()) >= 1, "the attach opened the agent pipe");
            assertEquals(0, scannerFailures.get(), "the pinned scanner failed a lookup, so auto-connect could not "
                    + "race the attach and the no-two-pipe-clients check above proved nothing");
        }
        assertFalse(script.events.stream().anyMatch(LauncherLiveTest::isDropped),
                "events were dropped: " + script.events);
    }

    /**
     * Acceptance 4: a script scoped to one account sees nothing of another,
     * cannot launch on it or stop its client, and every call is audited. The
     * other account ({@code botwithus.live.account}) must have a running client.
     */
    @Test
    void scopedScript_cannotSeeLaunchOrStopAnotherAccount() throws Exception {
        ClientLauncher whole = start(new LiveWholeHostManager(), new Target.Host());
        String y = liveAccount(whole);
        LaunchedClient yClient = whole.clients().stream().filter(c -> c.accountId().equals(y))
                .filter(c -> c.state() == LaunchedClient.State.INJECTED).findFirst()
                .orElseThrow(() -> new AssertionError("account " + y + " has no injected client to protect"));
        ClientLauncher scoped = start(new LiveScopedManager(), new Target.ClientScript(NOBODYS_ACCOUNT, "live"));
        List<LauncherAccount> seenAccounts = scoped.accounts();
        List<LaunchedClient> seenClients = scoped.clients();
        LauncherException launch = assertThrows(LauncherException.class, () -> scoped.launch(y));
        LauncherException stop = assertThrows(LauncherException.class,
                () -> scoped.stop(yClient.clientId(), StopMode.KILL));
        List<String> audit = ctx.getOrchestratorAudit().entries(LiveScopedManager.class.getSimpleName()).stream()
                .map(OrchestratorAuditLog.Entry::call).toList();
        System.out.printf("LIVE scope: accounts %s clients %s launch=%s stop=%s audit=%s%n", seenAccounts,
                seenClients.stream().map(LaunchedClient::clientId).toList(), launch.code(), stop.code(), audit);
        assertAll(() -> assertTrue(seenAccounts.stream().noneMatch(a -> a.id().equals(y)), "Y is visible"),
                () -> assertTrue(seenClients.stream().noneMatch(c -> c.accountId().equals(y)), "Y's client is visible"),
                () -> assertEquals(LauncherException.NOT_PERMITTED, launch.code()),
                () -> assertEquals(LauncherException.NOT_PERMITTED, stop.code()),
                () -> assertEquals(List.of("launcher.onEvent", "launcher.accounts", "launcher.clients",
                        "launcher.launch", "launcher.stop"), audit),
                () -> assertTrue(ProcessHandle.of(yClient.pid()).map(ProcessHandle::isAlive).orElse(false),
                        "Y's client was stopped"));
    }

    /**
     * Acceptance 5: kill the service and start it again while the host runs.
     * The host must register again within 30 s of the service coming back, and
     * must not start a service itself while there is none.
     */
    @Test
    void serviceRestart_reRegistersWithinThirtySeconds_andNeverStartsTheService() throws Exception {
        Path exe = Path.of(System.getProperty("botwithus.live.serviceExe"));
        long before = host.service().registrationCount();
        ProcessHandle service = runningService(exe).orElseThrow(() -> new AssertionError("no service at " + exe));
        System.out.printf("LIVE restart: killing service pid %d%n", service.pid());
        service.destroyForcibly();
        awaitTrue("the service to die", Duration.ofSeconds(10), () -> !service.isAlive());
        awaitTrue("the host to notice", Duration.ofSeconds(10), () -> !host.service().isConnected());
        Instant downUntil = Instant.now().plus(SERVICE_DOWN_FOR);
        while (Instant.now().isBefore(downUntil)) {
            assertEquals(Optional.empty(), anyService(), "a bwu_service started while none should run");
            Thread.sleep(Duration.ofMillis(250));
        }
        Process restarted = new ProcessBuilder(exe.toString()).directory(exe.getParent().toFile()).start();
        Instant up = Instant.now();
        System.out.printf("LIVE restart: started service pid %d at %s%n", restarted.pid(), up);
        awaitTrue("re-registration", REGISTER_WITHIN, () -> host.service().registrationCount() > before);
        Duration took = Duration.between(up, Instant.now());
        System.out.printf("LIVE restart: registered again %d ms after the service was started%n", took.toMillis());
        assertTrue(took.compareTo(REGISTER_WITHIN) <= 0);
    }

    /**
     * Auto-connect over a scanner that sees only the clients this test launched.
     * An unpinned scanner connects to every agent on the machine and runs
     * probeAndAutoStart on it, which would reach someone's own game. The pid is
     * looked up on every scan, so the scanner can still race the launcher's
     * attach, which is what the no-two-pipe-clients check is about.
     */
    private void startAutoConnect() {
        ctx.getSettings().set(SettingKeys.SCAN_INTERVAL_MS, FAST_SCAN_MS);
        ctx.getSettings().set(SettingKeys.AUTO_CONNECT, true);
        autoStart = new AutoStartManager(ctx, new ScriptProfileStore(tempDir.resolve("profiles")), ctx.getSettings(),
                AgentPin.scannerKeeping(this::isLaunchedHere));
        ctx.setAutoStartManager(autoStart);
        autoStart.start();
    }

    private ClientLauncher start(LiveManager script, Target target) throws Exception {
        ctx.getManagementTargets().add(script.getClass().getSimpleName(), target);
        ctx.getManagementRuntime().startScript(script);
        return script.launcher.get(REGISTER_WITHIN.toSeconds(), TimeUnit.SECONDS);
    }

    private static String liveAccount(ClientLauncher launcher) {
        List<LauncherAccount> accounts = launcher.accounts();
        System.out.println("LIVE accounts: " + accounts);
        String wanted = System.getProperty("botwithus.live.account");
        return accounts.stream().map(LauncherAccount::id).filter(id -> wanted == null || id.equals(wanted))
                .findFirst().orElseThrow(() -> new AssertionError("account " + wanted + " not in " + accounts));
    }

    /** A second login on one account would fight the first; stop any client already on it. */
    private static void stopExistingClients(ClientLauncher launcher, String account) throws InterruptedException {
        for (LaunchedClient c : launcher.clients()) {
            if (c.accountId().equals(account) && c.state() != LaunchedClient.State.EXITED
                    && c.state() != LaunchedClient.State.FAILED) {
                System.out.printf("LIVE: stopping existing client %s pid %d first%n", c.clientId(), c.pid());
                launcher.stop(c.clientId(), StopMode.GRACEFUL);
                awaitTrue("existing pid " + c.pid() + " to exit", EXIT_WITHIN,
                        () -> ProcessHandle.of(c.pid()).map(p -> !p.isAlive()).orElse(true));
            }
        }
    }

    /**
     * Whether {@code pid} belongs to a client this test launched, as the launcher
     * reports it now. Both outcomes that matter are counted:
     * <ul>
     *   <li>a "yes" while the client is not yet attached goes into
     *       {@link #scannerOfferedUnattached}, the evidence that a scan offered
     *       it inside the window before the attach. A "yes" after the attach is
     *       not counted: every scan through the lobby wait reports the pid, so
     *       counting those would grow without any race having been possible;</li>
     *   <li>a failed lookup, of any kind, answers "no" and goes into
     *       {@link #scannerFailures}.</li>
     * </ul>
     * Zero failures alone would not prove the race ran: the scanner might simply
     * never have reported the launched pid.
     */
    private boolean isLaunchedHere(long pid) {
        ClientLauncher launcher = ownLauncher;
        if (launcher == null) {
            return false;
        }
        boolean isOurs;
        try {
            isOurs = launcher.clients().stream()
                    .anyMatch(c -> launchedHere.contains(c.clientId()) && c.pid() == pid);
        } catch (RuntimeException e) {
            scannerFailures.incrementAndGet();
            System.out.printf("LIVE: pinned scanner lookup failed: %s%n", e);
            return false;
        }
        if (isOurs) {
            if (isUnattached(pid)) {
                scannerOfferedUnattached.incrementAndGet();
            }
        }
        return isOurs;
    }

    /** Whether the host holds no connection to {@code pid}'s agent pipe yet. */
    private boolean isUnattached(long pid) {
        String pipe = PipeClient.NAME_PREFIX + pid;
        return ctx.getConnections().stream().noneMatch(c -> c.getName().equals(pipe));
    }

    private LaunchedClient launchAndReachLobby(ClientLauncher launcher, String account) throws Exception {
        ownLauncher = launcher;
        LaunchHandle handle = launcher.launch(account);
        launchedHere.add(handle.clientId());
        LaunchOutcome outcome = handle.outcome().toCompletableFuture().get(LAUNCH_WITHIN.toSeconds(), TimeUnit.SECONDS);
        System.out.printf("LIVE launch %s: %s%n", handle.clientId(), outcome);
        String connection = switch (outcome) {
            case LaunchOutcome.Attached attached -> attached.connectionName();
            default -> throw new AssertionError("launch " + handle.clientId() + " ended " + outcome);
        };
        LaunchedClient client = launcher.clients().stream().filter(c -> c.clientId().equals(handle.clientId()))
                .findFirst().orElseThrow();
        assertEquals(PipeClient.NAME_PREFIX + client.pid(), connection);
        awaitTrue("the lobby on " + connection, LOBBY_WITHIN, () -> ctx.getConnections().stream()
                .filter(c -> c.getName().equals(connection))
                .anyMatch(c -> c.getGameStatus().state() == GameState.LOBBY));
        System.out.printf("LIVE: %s reached the lobby%n", connection);
        return client;
    }

    private static Optional<ProcessHandle> runningService(Path exe) {
        return ProcessHandle.allProcesses().filter(p -> p.info().command()
                .map(c -> Path.of(c).equals(exe)).orElse(false)).findFirst();
    }

    private static Optional<ProcessHandle> anyService() {
        return ProcessHandle.allProcesses().filter(p -> p.info().command()
                .map(c -> c.toLowerCase(Locale.ROOT).endsWith("bwu_service.exe")).orElse(false)).findFirst();
    }

    private static boolean isDropped(LauncherEvent event) {
        return switch (event) {
            case LauncherEvent.EventsDropped dropped -> true;
            default -> false;
        };
    }

    private static void awaitTrue(String what, Duration timeout, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out after " + timeout + " waiting for " + what);
            }
            Thread.sleep(Duration.ofMillis(200));
        }
    }

    /** A management script that hands its launcher to the test and records its events. */
    abstract static class LiveManager implements ManagementScript {

        final CompletableFuture<ClientLauncher> launcher = new CompletableFuture<>();
        final List<LauncherEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onStart(ManagementContext context) {
            ClientLauncher mine = context.clientLauncher();
            mine.onEvent(events::add);
            launcher.complete(mine);
        }

        @Override
        public int onLoop() {
            return (int) Duration.ofSeconds(1).toMillis();
        }

        @Override
        public void onStop() {
            // Nothing to release: the subscription ends with the host.
        }

        Optional<LauncherEvent.ClientExited> exited(String clientId) {
            return events.stream().flatMap(e -> switch (e) {
                case LauncherEvent.ClientExited x when x.clientId().equals(clientId) -> Stream.of(x);
                default -> Stream.<LauncherEvent.ClientExited>empty();
            }).findFirst();
        }

        /** What happened to {@code clientId}, in order: event names, with the state for a state change. */
        List<String> kindsFor(String clientId) {
            List<String> kinds = new ArrayList<>();
            for (LauncherEvent e : events) {
                switch (e) {
                    case LauncherEvent.ClientStarted s when s.client().clientId().equals(clientId) ->
                            kinds.add("ClientStarted:" + s.client().state());
                    case LauncherEvent.ClientStateChanged s when s.clientId().equals(clientId) ->
                            kinds.add("ClientStateChanged:" + s.state());
                    case LauncherEvent.ClientExited s when s.clientId().equals(clientId) ->
                            kinds.add("ClientExited:" + s.reason());
                    default -> {
                        // About another client, or not about a client.
                    }
                }
            }
            return kinds;
        }
    }

    /** Whole-host manager: launches and stops. */
    static final class LiveWholeHostManager extends LiveManager {
    }

    /** Scoped to one account nobody has. */
    static final class LiveScopedManager extends LiveManager {
    }

    /**
     * Every agent pipe client the host opens and closes, from
     * {@code PipeClient}'s DEBUG lines, so "never two at once" is measured.
     */
    static final class PipeOpens {

        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        static PipeOpens watch() {
            PipeOpens opens = new PipeOpens();
            // rule-exception: {rule:no-casts} - SLF4J/Logback binding boundary, as in ImGuiApp:
            // getILoggerFactory() is typed ILoggerFactory; reading DEBUG lines needs Logback's own.
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            Logger logger = context.getLogger(PipeClient.class);
            logger.setLevel(Level.DEBUG);
            opens.appender.start();
            logger.addAppender(opens.appender);
            return opens;
        }

        int maxOpenAtOnce(long pid) {
            int open = 0;
            int max = 0;
            for (String line : log(pid)) {
                open += line.startsWith("Pipe client opened") ? 1 : -1;
                max = Math.max(max, open);
            }
            return max;
        }

        long opensFor(long pid) {
            return log(pid).stream().filter(line -> line.startsWith("Pipe client opened")).count();
        }

        List<String> log(long pid) {
            String suffix = PipeClient.NAME_PREFIX + pid;
            List<ILoggingEvent> events;
            // AppenderBase.doAppend appends while holding the appender's monitor.
            synchronized (appender) {
                events = List.copyOf(appender.list);
            }
            return events.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.endsWith(suffix)).toList();
        }
    }
}
