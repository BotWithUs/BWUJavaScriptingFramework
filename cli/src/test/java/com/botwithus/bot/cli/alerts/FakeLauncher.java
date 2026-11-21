package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertDelivery;
import com.botwithus.bot.core.alerts.AlertGateway;
import com.botwithus.bot.core.alerts.AlertMessage;
import com.botwithus.bot.core.alerts.AlertResult;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.DeliverReply;
import com.botwithus.bot.core.alerts.DeliveryOutcome;
import com.botwithus.bot.core.alerts.IntegrationChange;
import com.botwithus.bot.core.alerts.IntegrationReply;
import com.botwithus.bot.core.alerts.IntegrationState;
import com.botwithus.bot.core.alerts.ServiceState;
import com.botwithus.bot.core.alerts.TestReply;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the launcher service's {@code alerts.*} methods. It records every
 * call, queues every delivery unless a test says otherwise, and reports results
 * only when the test asks ({@link #reportAll()}), the way the launcher reports them
 * after its answer. It never sends anything anywhere.
 */
public final class FakeLauncher implements AlertGateway {

    /** How the launcher words a webhook URL that is not https; the Java host's own wording. */
    public static final String HTTPS_REQUIRED = "The webhook URL must start with https://";
    private static final String HTTPS = "https://";
    private static final Duration DEFAULT_ELAPSED = Duration.ofMillis(120);

    private final Clock clock;
    private final List<AlertDelivery> deliveries = new CopyOnWriteArrayList<>();
    private final List<IntegrationChange> changes = new CopyOnWriteArrayList<>();
    private final List<AlertService> tests = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<AlertResult> unreported = new ArrayList<>();
    private final Map<AlertService, DeliveryOutcome.DropReason> drops = new EnumMap<>(AlertService.class);
    private final Map<AlertService, AlertResult.Outcome> outcomes = new EnumMap<>(AlertService.class);
    private final Map<AlertService, IntegrationState> setups = new EnumMap<>(AlertService.class);
    private final Map<AlertService, String> savedSecrets = new EnumMap<>(AlertService.class);
    private final List<ServiceState> status = new ArrayList<>();
    private Optional<IntegrationReply> nextChangeReply = Optional.empty();
    private Optional<TestReply> nextTestReply = Optional.empty();
    private boolean isAvailable = true;
    private boolean isConnected = true;
    private int unanswered;
    private long nextId = 1;
    private Duration elapsed = DEFAULT_ELAPSED;

    public FakeLauncher(Clock clock) {
        this.clock = clock;
        for (AlertService service : AlertService.values()) {
            setups.put(service, IntegrationState.unknown(service));
        }
    }

    // ── What a test sets up ────────────────────────────────────────────────

    /** An older launcher: connected, but without the alert methods. */
    public FakeLauncher predatingAlerts() {
        isAvailable = false;
        return this;
    }

    /** No launcher at all. */
    public FakeLauncher gone() {
        isAvailable = false;
        isConnected = false;
        return this;
    }

    /** The launcher comes (back) with the alert methods, and says so. */
    public void comeBack() {
        isAvailable = true;
        isConnected = true;
        listeners.forEach(Listener::onAvailable);
    }

    /** The next {@code count} deliveries get no answer, as a call that timed out. */
    public void leaveUnanswered(int count) {
        unanswered = count;
    }

    public FakeLauncher drop(AlertService service, DeliveryOutcome.DropReason reason) {
        drops.put(service, reason);
        return this;
    }

    /** Sends to {@code service} end as {@code outcome} when reported; delivered otherwise. */
    public FakeLauncher answer(AlertService service, AlertResult.Outcome outcome) {
        outcomes.put(service, outcome);
        return this;
    }

    /** Sends report {@code elapsed} when they are delivered. */
    public FakeLauncher elapsed(Duration took) {
        elapsed = took;
        return this;
    }

    /** The secret the host last handed over for {@code service}; empty if none, or it was cleared. */
    public Optional<String> savedSecret(AlertService service) {
        return Optional.ofNullable(savedSecrets.get(service));
    }

    public void setup(IntegrationState state) {
        setups.put(state.service(), state);
    }

    public void status(ServiceState state) {
        status.add(state);
    }

    public void replyToNextChange(IntegrationReply reply) {
        nextChangeReply = Optional.of(reply);
    }

    public void replyToNextTest(TestReply reply) {
        nextTestReply = Optional.of(reply);
    }

    /** Reports every queued send's result, oldest first. */
    public void reportAll() {
        List<AlertResult> due;
        synchronized (unreported) {
            due = List.copyOf(unreported);
            unreported.clear();
        }
        due.forEach(this::report);
    }

    public void report(AlertResult result) {
        listeners.forEach(listener -> listener.onResult(result));
    }

    // ── What a test reads back ─────────────────────────────────────────────

    public List<AlertDelivery> deliveries() {
        return List.copyOf(deliveries);
    }

    public List<AlertMessage> sentTo(AlertService service) {
        return deliveries.stream().filter(d -> d.services().contains(service)).map(AlertDelivery::message).toList();
    }

    public List<IntegrationChange> changes() {
        return List.copyOf(changes);
    }

    public List<AlertService> tests() {
        return List.copyOf(tests);
    }

    // ── AlertGateway ───────────────────────────────────────────────────────

    @Override
    public boolean isAvailable() {
        return isAvailable;
    }

    @Override
    public boolean isConnected() {
        return isConnected;
    }

    @Override
    public DeliverReply deliver(AlertDelivery delivery) {
        if (!isAvailable) {
            return new DeliverReply.Unavailable(AlertGateway.NEEDS_NEWER_LAUNCHER);
        }
        deliveries.add(delivery);
        if (unanswered > 0) {
            unanswered--;
            return new DeliverReply.Unavailable("timed out");
        }
        List<DeliveryOutcome> answer = new ArrayList<>();
        for (AlertService service : delivery.services()) {
            answer.add(outcomeFor(service, delivery));
        }
        return new DeliverReply.Answered(answer);
    }

    @Override
    public Optional<List<IntegrationState>> integrations() {
        return isAvailable ? Optional.of(List.copyOf(setups.values())) : Optional.empty();
    }

    @Override
    public IntegrationReply change(IntegrationChange change) {
        changes.add(change);
        if (nextChangeReply.isPresent()) {
            IntegrationReply reply = nextChangeReply.get();
            nextChangeReply = Optional.empty();
            return reply;
        }
        if (!isAvailable) {
            return new IntegrationReply.Unavailable(AlertGateway.NEEDS_NEWER_LAUNCHER);
        }
        boolean isWebhook = change.service() != AlertService.NTFY;
        if (isWebhook && change.secret().filter(secret -> !secret.reveal().startsWith(HTTPS)).isPresent()) {
            return new IntegrationReply.Refused(Optional.of("secret"), HTTPS_REQUIRED);
        }
        IntegrationState now = setups.get(change.service());
        boolean hasSecret = change.secret().isPresent() || (now.hasSecret() && !change.isClearingSecret());
        IntegrationState next = new IntegrationState(change.service(), hasSecret,
                hasSecret ? Optional.of("hint/…") : Optional.empty(),
                change.isMentioningHere().orElse(now.isMentioningHere()), change.server().orElse(now.server()),
                change.topic().orElse(now.topic()));
        setups.put(change.service(), next);
        change.secret().ifPresent(secret -> savedSecrets.put(change.service(), secret.reveal()));
        if (change.isClearingSecret()) {
            savedSecrets.remove(change.service());
        }
        return new IntegrationReply.Updated(next);
    }

    @Override
    public TestReply sendTest(AlertService service) {
        tests.add(service);
        if (nextTestReply.isPresent()) {
            TestReply reply = nextTestReply.get();
            nextTestReply = Optional.empty();
            return reply;
        }
        if (!isAvailable) {
            return new TestReply.Unavailable(AlertGateway.NEEDS_NEWER_LAUNCHER);
        }
        IntegrationState setup = setups.get(service);
        boolean isSetUp = service == AlertService.NTFY ? !setup.topic().isEmpty() : setup.hasSecret();
        if (!isSetUp) {
            return new TestReply.Refused(AlertSender.notConfigured(service));
        }
        long id = nextId++;
        queueResult(id, service, true, "test");
        return new TestReply.Queued(id);
    }

    @Override
    public Optional<List<ServiceState>> status() {
        return isAvailable ? Optional.of(List.copyOf(status)) : Optional.empty();
    }

    @Override
    public Runnable addListener(Listener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private DeliveryOutcome outcomeFor(AlertService service, AlertDelivery delivery) {
        DeliveryOutcome.DropReason drop = drops.get(service);
        if (drop != null) {
            return new DeliveryOutcome(service, false, OptionalLong.empty(), Optional.of(drop));
        }
        long id = nextId++;
        queueResult(id, service, false, delivery.kind());
        return new DeliveryOutcome(service, true, OptionalLong.of(id), Optional.empty());
    }

    private void queueResult(long id, AlertService service, boolean isTest, String kind) {
        AlertResult.Outcome outcome = outcomes.getOrDefault(service, AlertResult.Outcome.DELIVERED);
        boolean isDelivered = outcome == AlertResult.Outcome.DELIVERED;
        AlertResult result = new AlertResult(id, service, isTest, kind, outcome, isDelivered ? "" : "429",
                isDelivered ? "" : "Too many requests", isDelivered ? 1 : 3, elapsed, clock.instant());
        synchronized (unreported) {
            unreported.add(result);
        }
    }
}
