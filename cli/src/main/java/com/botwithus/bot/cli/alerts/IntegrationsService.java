package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertGateway;
import com.botwithus.bot.core.alerts.AlertResult;
import com.botwithus.bot.core.alerts.AlertScheduler;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.IntegrationChange;
import com.botwithus.bot.core.alerts.IntegrationReply;
import com.botwithus.bot.core.alerts.IntegrationState;
import com.botwithus.bot.core.alerts.SendResult;
import com.botwithus.bot.core.alerts.ServiceState;
import com.botwithus.bot.core.alerts.TestReply;
import com.botwithus.bot.core.secrets.CredentialStore;
import com.botwithus.bot.core.secrets.Secret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * {@link Integrations} over the launcher: each service's set-up is read from and
 * written to the launcher, and Send test asks the launcher to send one and waits
 * for its {@code alerts.result}.
 *
 * <p>The set-up is read when a launcher that sends alerts connects, after the
 * host's old set-up has been {@link IntegrationMigration moved} to it, and kept
 * up to date from the launcher's answers, so {@link #services()} never touches
 * the pipe and is cheap enough for every frame. Every call that does runs on the
 * {@code background} executor.</p>
 */
public final class IntegrationsService implements Integrations, AlertGateway.Listener {

    /** How long Send test waits for the launcher's result: its retries and a long Retry-After fit well inside. */
    static final Duration TEST_TIMEOUT = Duration.ofMinutes(3);
    static final String NO_ANSWER = "No answer";
    static final String TIMED_OUT = "The launcher did not report a result";
    static final String WENT_AWAY = "The launcher went away before the test finished";
    /** Early test results kept for an answer still on its way; the oldest are of no use. */
    static final int MAX_EARLY_RESULTS = 16;
    static final String TOO_LONG = "The %s can be at most " + CredentialStore.MAX_SECRET_CHARS + " characters";

    private static final Logger log = LoggerFactory.getLogger(IntegrationsService.class);

    /** A test the launcher queued and has not reported on yet. */
    private record PendingTest(AlertService service, CompletableFuture<SendResult> done) { }

    private final AlertSettings settings;
    private final AlertGateway gateway;
    private final IntegrationMigration migration;
    private final ServiceStatusBoard status;
    private final AlertScheduler scheduler;
    private final Executor background;
    private final Clock clock;
    private final ZoneId zone;
    private final Map<AlertService, IntegrationState> setups = new ConcurrentHashMap<>();
    private final Map<IntegrationField, String> fieldErrors = new ConcurrentHashMap<>();
    private final Map<Long, PendingTest> pendingTests = new ConcurrentHashMap<>();
    /** Test results that arrived before the launcher's answer to the test; rarely more than one. */
    private final Map<Long, SendResult> earlyResults = new ConcurrentHashMap<>();

    /**
     * @param migration  moves the host's old set-up to the launcher when one connects
     * @param scheduler  times out a test the launcher never reports on
     * @param background runs every call to the launcher, off the render thread
     */
    public IntegrationsService(AlertSettings settings, AlertGateway gateway, IntegrationMigration migration,
                               ServiceStatusBoard status, AlertScheduler scheduler, Executor background,
                               Clock clock, ZoneId zone) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.migration = Objects.requireNonNull(migration, "migration");
        this.status = Objects.requireNonNull(status, "status");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.background = Objects.requireNonNull(background, "background");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override
    public Availability availability() {
        if (gateway.isAvailable()) {
            return Availability.READY;
        }
        return gateway.isConnected() ? Availability.NEEDS_NEWER_LAUNCHER : Availability.NO_LAUNCHER;
    }

    @Override
    public List<ServiceView> services() {
        return Arrays.stream(AlertService.values()).map(this::service).toList();
    }

    @Override
    public ServiceView service(AlertService service) {
        boolean isEnabled = settings.isEnabled(service);
        return new ServiceView(service, isEnabled, setups.getOrDefault(service, IntegrationState.unknown(service)),
                status.status(service, isEnabled), status.last(service).map(send -> send.line(zone)));
    }

    @Override
    public CompletableFuture<SecretChange> saveSecret(AlertService service, String value) {
        String text = value == null ? "" : value.strip();
        if (text.length() > CredentialStore.MAX_SECRET_CHARS) {
            return CompletableFuture.completedFuture(new SecretChange.Refused(
                    TOO_LONG.formatted(secretName(service))));
        }
        IntegrationChange change = text.isEmpty() ? IntegrationChange.clearSecret(service)
                : IntegrationChange.secret(service, new Secret(text));
        return CompletableFuture.supplyAsync(() -> secretChange(gateway.change(change), text.isEmpty()), background);
    }

    @Override
    public void change(IntegrationField field, String value) {
        fieldErrors.remove(field);
        IntegrationChange change = field.change(value);
        background.execute(() -> {
            switch (gateway.change(change)) {
                case IntegrationReply.Updated updated -> setups.put(updated.state().service(), updated.state());
                case IntegrationReply.Refused refused -> fieldErrors.put(field, refused.message());
                case IntegrationReply.Unavailable unavailable -> fieldErrors.put(field, unavailable.message());
            }
        });
    }

    @Override
    public Optional<String> fieldError(IntegrationField field) {
        return Optional.ofNullable(fieldErrors.get(field));
    }

    @Override
    public CompletableFuture<SendResult> sendTest(AlertService service) {
        CompletableFuture<SendResult> done = new CompletableFuture<>();
        status.markSending(service);
        background.execute(() -> askForTest(service, done));
        return done;
    }

    /** A launcher that sends alerts connected: move the old set-up, then read the set-up and status. */
    @Override
    public void onAvailable() {
        background.execute(() -> {
            List<IntegrationMigration.Step> moved = migration.run();
            log.debug("Alerts: migration steps {}", moved);
            refresh();
            seedStatus();
        });
    }

    @Override
    public void onUnavailable() {
        for (Long id : List.copyOf(pendingTests.keySet())) {
            finish(id, new SendResult.Failed(clock.instant(), NO_ANSWER, WENT_AWAY, 0));
        }
    }

    @Override
    public void onResult(AlertResult result) {
        if (!result.isTest()) {
            return;
        }
        if (!finish(result.deliveryId(), result.toSendResult())) {
            status.record(result.service(), LastSend.test(result.toSendResult()));
            keepEarly(result.deliveryId(), result.toSendResult());
        }
    }

    /** Re-reads every service's set-up from the launcher. */
    void refresh() {
        gateway.integrations().ifPresent(states -> states.forEach(state -> setups.put(state.service(), state)));
    }

    private SecretChange secretChange(IntegrationReply reply, boolean isClearing) {
        return switch (reply) {
            case IntegrationReply.Updated updated -> {
                setups.put(updated.state().service(), updated.state());
                yield isClearing ? new SecretChange.Cleared() : new SecretChange.Saved();
            }
            case IntegrationReply.Refused refused -> new SecretChange.Refused(refused.message());
            case IntegrationReply.Unavailable unavailable -> new SecretChange.Refused(unavailable.message());
        };
    }

    private void askForTest(AlertService service, CompletableFuture<SendResult> done) {
        switch (gateway.sendTest(service)) {
            case TestReply.Queued queued -> {
                pendingTests.put(queued.deliveryId(), new PendingTest(service, done));
                Optional.ofNullable(earlyResults.remove(queued.deliveryId()))
                        .ifPresent(early -> finish(queued.deliveryId(), early));
                scheduler.schedule(clock.instant().plus(TEST_TIMEOUT), () -> finish(queued.deliveryId(),
                        new SendResult.Failed(clock.instant(), NO_ANSWER, TIMED_OUT, 0)));
            }
            case TestReply.Refused refused -> record(service, done,
                    new SendResult.Failed(clock.instant(), AlertSender.NOT_SET_UP, refused.message(), 0));
            case TestReply.Unavailable unavailable -> record(service, done,
                    new SendResult.Failed(clock.instant(), AlertSender.NOT_SENT, unavailable.message(), 0));
        }
    }

    /** @return whether {@code deliveryId} was a test waiting for its result */
    private boolean finish(long deliveryId, SendResult result) {
        PendingTest test = pendingTests.remove(deliveryId);
        if (test == null) {
            return false;
        }
        record(test.service(), test.done(), result);
        return true;
    }

    private void record(AlertService service, CompletableFuture<SendResult> done, SendResult result) {
        status.record(service, LastSend.test(result));
        done.complete(result);
    }

    private void keepEarly(long deliveryId, SendResult result) {
        earlyResults.put(deliveryId, result);
        if (earlyResults.size() > MAX_EARLY_RESULTS) {
            earlyResults.keySet().stream().filter(id -> id != deliveryId).findFirst().ifPresent(earlyResults::remove);
        }
    }

    /** The secret's label for use mid-sentence: "webhook URL", "access token". */
    static String secretName(AlertService service) {
        String label = service.secretLabel();
        return label.substring(0, 1).toLowerCase(Locale.ROOT) + label.substring(1);
    }

    /** Shows the launcher's last send to each service the host has not sent to since it started. */
    private void seedStatus() {
        gateway.status().ifPresent(states -> states.forEach(this::seed));
    }

    private void seed(ServiceState state) {
        if (status.last(state.service()).isPresent()) {
            return;
        }
        state.last().ifPresent(last -> status.record(state.service(), new LastSend(last.isTest(),
                last.toSendResult(), last.isTest() ? "" : AlertSender.describeKind(last.kind()))));
    }
}
