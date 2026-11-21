package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.events.ConnectionHistory;
import com.botwithus.bot.cli.events.HostEventBus;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.Subscription;
import com.botwithus.bot.core.alerts.AlertGateway;
import com.botwithus.bot.core.alerts.VirtualThreadScheduler;
import com.botwithus.bot.core.secrets.CredentialStore;
import com.botwithus.bot.core.secrets.CredentialStoreException;
import com.botwithus.bot.core.secrets.InMemoryCredentialStore;
import com.botwithus.bot.core.secrets.WindowsCredentialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The host's alert machinery, wired: the dispatcher on the host event bus, the
 * daily summary job, and the {@link Integrations} the Settings page binds to.
 * Alerts that quiet hours hold back are kept in {@value HeldAlerts#FILE_NAME} beside
 * the settings file, so a restart during quiet hours still sends them when they end.
 *
 * <p>Built once by the composition root, before the host registers with the
 * launcher service; {@link #bindLauncher} then connects it. The launcher sends
 * every alert and keeps every service's set-up: this host makes no request of its
 * own. Until a launcher that sends alerts is connected, alerts wait. The host's
 * credential store is opened only to move the set-up it kept before to the
 * launcher, once.</p>
 */
public final class Alerts implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Alerts.class);
    private static final String INTAKE_THREAD = "alerts-intake";
    private static final String SEND_THREAD = "alerts-send";
    private static final String LAUNCHER_THREAD = "alerts-launcher";

    /** The threads the machinery runs on, shut down together. */
    private record Workers(ExecutorService intake, ExecutorService sends, ExecutorService background) {

        static Workers start() {
            return new Workers(single(INTAKE_THREAD), single(SEND_THREAD), single(LAUNCHER_THREAD));
        }

        void shutdown() {
            List.of(intake, sends, background).forEach(ExecutorService::shutdown);
        }

        private static ExecutorService single(String name) {
            return Executors.newSingleThreadExecutor(Thread.ofVirtual().name(name).factory());
        }
    }

    private final LauncherLink link;
    private final Integrations integrations;
    private final Runnable unsubscribe;
    private final List<Runnable> unlisten;
    private final List<Subscription> watches;
    private final AlertDispatcher dispatcher;
    private final DailySummaryJob summaryJob;
    private final Workers workers;

    private Alerts(LauncherLink link, IntegrationsService integrations, AlertSender sender, Runnable unsubscribe,
                   List<Subscription> watches, AlertDispatcher dispatcher, DailySummaryJob summaryJob,
                   Workers workers) {
        this.link = link;
        this.integrations = integrations;
        this.unsubscribe = unsubscribe;
        this.unlisten = List.of(link.addListener(sender), link.addListener(integrations));
        this.watches = List.copyOf(watches);
        this.dispatcher = dispatcher;
        this.summaryJob = summaryJob;
        this.workers = workers;
    }

    /**
     * Subscribes the dispatcher to {@code bus} and starts the daily summary job.
     *
     * @param directory   names and states of clients, for alerts and the summary
     * @param history     the host's event history, for the summary
     * @param credentials the host's own credential store, where an older host kept each
     *                    service's secret; read once to move it to the launcher
     */
    public static Alerts start(HostSettings hostSettings, HostEventBus bus, ClientDirectory directory,
                               ConnectionHistory history, CredentialStore credentials) {
        Clock clock = Clock.systemUTC();
        ZoneId zone = ZoneId.systemDefault();
        AlertSettings settings = new AlertSettings(hostSettings);
        LauncherLink link = new LauncherLink();
        ServiceStatusBoard status = new ServiceStatusBoard();
        Workers workers = Workers.start();
        VirtualThreadScheduler scheduler = new VirtualThreadScheduler(clock);
        AlertSender sender = new AlertSender(link, status, workers.sends(), clock);
        HeldAlerts held = HeldAlerts.open(hostSettings.file().resolveSibling(HeldAlerts.FILE_NAME));
        AlertDispatcher dispatcher = new AlertDispatcher(settings, new AlertClassifier(directory), sender,
                scheduler, workers.intake(), held, clock, zone);
        DailySummaryJob summaryJob = new DailySummaryJob(scheduler, clock, zone, settings::summaryAt,
                () -> dispatcher.dispatch(DailySummary.compose(clock.instant(), directory.clients(), history)));
        IntegrationMigration migration = new IntegrationMigration(link, credentials, hostSettings);
        IntegrationsService integrations = new IntegrationsService(settings, link, migration, status, scheduler,
                workers.background(), clock, zone);
        Runnable unsubscribe = bus.subscribe(dispatcher);
        Subscription quietWatch = dispatcher.resumeHeld();
        summaryJob.start();
        Subscription summaryWatch = settings.onSummaryTimeChange(summaryJob::reschedule);
        return new Alerts(link, integrations, sender, unsubscribe, List.of(quietWatch, summaryWatch), dispatcher,
                summaryJob, workers);
    }

    /**
     * Connects the machinery to the launcher service. Alerts that were waiting go
     * out once it can send them, and the host's old set-up moves to it.
     */
    public void bindLauncher(AlertGateway gateway) {
        link.bind(gateway);
    }

    /** What the Integrations section binds to. */
    public Integrations integrations() {
        return integrations;
    }

    /**
     * Stops taking events and cancels the pending summary and the pending send of
     * held alerts, which stay saved for the next start. Hand-overs already queued
     * may still finish; alerts still waiting for a launcher are dropped.
     */
    @Override
    public void close() {
        unsubscribe.run();
        watches.forEach(Subscription::close);
        unlisten.forEach(Runnable::run);
        dispatcher.close();
        summaryJob.close();
        workers.shutdown();
    }

    /**
     * The host's own credential store, read only for the one-time move. Where it
     * cannot be opened there is nothing to move: an empty store stands in.
     */
    public static CredentialStore openHostCredentials() {
        try {
            return new WindowsCredentialStore();
        } catch (CredentialStoreException e) {
            log.warn("Windows Credential Manager is not available; any alert set-up saved there by an older "
                    + "host stays there: {}", e.getMessage());
            return new InMemoryCredentialStore();
        }
    }
}
