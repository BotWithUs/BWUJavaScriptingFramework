package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.events.ConnectionHistory;
import com.botwithus.bot.cli.events.HostEventBus;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.Subscription;
import com.botwithus.bot.core.alerts.HttpDelivery;
import com.botwithus.bot.core.alerts.JdkHttpTransport;
import com.botwithus.bot.core.alerts.RetryPolicy;
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
 * <p>Built once by the composition root. Secrets go to Windows Credential
 * Manager; where it cannot be opened they are kept in memory for the session and
 * {@link Integrations#isPersistent()} says so.</p>
 */
public final class Alerts implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Alerts.class);
    private static final String INTAKE_THREAD = "alerts-intake";

    /** The credential store the host could open, and whether it survives a restart. */
    private record OpenedStore(CredentialStore store, boolean isPersistent) { }

    private final Integrations integrations;
    private final Runnable unsubscribe;
    private final List<Subscription> watches;
    private final AlertDispatcher dispatcher;
    private final DailySummaryJob summaryJob;
    private final ExecutorService intake;
    private final VirtualDeliveryLanes lanes;
    private final JdkHttpTransport transport;

    private Alerts(Integrations integrations, Runnable unsubscribe, List<Subscription> watches,
                   AlertDispatcher dispatcher, DailySummaryJob summaryJob, ExecutorService intake,
                   VirtualDeliveryLanes lanes, JdkHttpTransport transport) {
        this.integrations = integrations;
        this.unsubscribe = unsubscribe;
        this.watches = List.copyOf(watches);
        this.dispatcher = dispatcher;
        this.summaryJob = summaryJob;
        this.intake = intake;
        this.lanes = lanes;
        this.transport = transport;
    }

    /**
     * Subscribes the dispatcher to {@code bus} and starts the daily summary job.
     *
     * @param directory names and states of clients, for alerts and the summary
     * @param history   the host's event history, for the summary
     */
    public static Alerts start(HostSettings hostSettings, HostEventBus bus, ClientDirectory directory,
                               ConnectionHistory history) {
        Clock clock = Clock.systemUTC();
        ZoneId zone = ZoneId.systemDefault();
        AlertSettings settings = new AlertSettings(hostSettings);
        OpenedStore opened = openCredentials();
        CredentialStore credentials = opened.store();
        JdkHttpTransport transport = new JdkHttpTransport();
        HttpDelivery delivery = new HttpDelivery(transport, RetryPolicy.DEFAULT, Thread::sleep, clock);
        NotifierSource notifiers = new LiveNotifierSource(settings, credentials, delivery);
        ServiceStatusBoard status = new ServiceStatusBoard();
        VirtualDeliveryLanes lanes = new VirtualDeliveryLanes();
        ExecutorService intake = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name(INTAKE_THREAD).factory());
        VirtualThreadScheduler scheduler = new VirtualThreadScheduler(clock);
        HeldAlerts held = HeldAlerts.open(hostSettings.file().resolveSibling(HeldAlerts.FILE_NAME));
        AlertDispatcher dispatcher = new AlertDispatcher(settings, new AlertClassifier(directory), notifiers,
                status, scheduler, intake, lanes, held, clock, zone);
        DailySummaryJob summaryJob = new DailySummaryJob(scheduler, clock, zone, settings::summaryAt,
                () -> dispatcher.dispatch(DailySummary.compose(clock.instant(), directory.clients(), history)));
        Integrations integrations = new IntegrationsService(settings, credentials, opened.isPersistent(), notifiers,
                status, lanes, clock, zone);
        Runnable unsubscribe = bus.subscribe(dispatcher);
        Subscription quietWatch = dispatcher.resumeHeld();
        summaryJob.start();
        Subscription summaryWatch = settings.onSummaryTimeChange(summaryJob::reschedule);
        return new Alerts(integrations, unsubscribe, List.of(quietWatch, summaryWatch), dispatcher, summaryJob,
                intake, lanes, transport);
    }

    /** What the Integrations section binds to. */
    public Integrations integrations() {
        return integrations;
    }

    /**
     * Stops taking events and cancels the pending summary and the pending send of
     * held alerts, which stay saved for the next start. Sends already queued may
     * still finish.
     */
    @Override
    public void close() {
        unsubscribe.run();
        watches.forEach(Subscription::close);
        dispatcher.close();
        summaryJob.close();
        intake.shutdown();
        lanes.close();
        transport.close();
    }

    private static OpenedStore openCredentials() {
        try {
            return new OpenedStore(new WindowsCredentialStore(), true);
        } catch (CredentialStoreException e) {
            log.warn("Windows Credential Manager is not available; alert secrets are kept for this "
                    + "session only: {}", e.getMessage());
            return new OpenedStore(new InMemoryCredentialStore(), false);
        }
    }
}
