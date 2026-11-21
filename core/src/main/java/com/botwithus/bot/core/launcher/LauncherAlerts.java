package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.LauncherEvent;
import com.botwithus.bot.core.alerts.AlertDelivery;
import com.botwithus.bot.core.alerts.AlertGateway;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.DeliverReply;
import com.botwithus.bot.core.alerts.IntegrationChange;
import com.botwithus.bot.core.alerts.IntegrationReply;
import com.botwithus.bot.core.alerts.IntegrationState;
import com.botwithus.bot.core.alerts.ServiceState;
import com.botwithus.bot.core.alerts.TestReply;
import com.botwithus.msgpack.MsgValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@link AlertGateway} over this host's connection to the launcher service: the
 * {@code alerts.*} methods and the {@code alerts.result} event.
 *
 * <p>Whether alerts can go out is read from the connected service's {@code hello}:
 * it must list every one of {@link LauncherProtocol#ALERT_METHODS}. When it does
 * not (an older launcher), or no service is connected, every call answers
 * unavailable without touching the pipe. This class never makes a request of its
 * own to anywhere but the service's pipe.</p>
 */
public final class LauncherAlerts implements AlertGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LauncherAlerts.class);
    private static final String BAD_REQUEST = "bad_request";
    private static final String UNKNOWN_METHOD = "unknown_method";
    private static final String NOT_SAVED = "The launcher could not save it";

    private final LauncherService service;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> detach;

    private LauncherAlerts(LauncherService service) {
        this.service = service;
        this.detach = List.of(service.addRegistrationListener(this::onRegistered),
                service.addListener(this::onLauncherEvent),
                service.addEventTap(LauncherProtocol.EVENT_ALERTS_RESULT, this::onResult));
    }

    /** Follows {@code service}'s connection; attach before {@link LauncherService#start()} to hear the first. */
    public static LauncherAlerts attach(LauncherService service) {
        return new LauncherAlerts(Objects.requireNonNull(service, "service"));
    }

    @Override
    public boolean isAvailable() {
        return LauncherProtocol.ALERT_METHODS.stream().allMatch(service::offers);
    }

    @Override
    public boolean isConnected() {
        return service.isConnected();
    }

    @Override
    public DeliverReply deliver(AlertDelivery delivery) {
        if (!isAvailable()) {
            return new DeliverReply.Unavailable(unavailableMessage());
        }
        return switch (service.exchange(LauncherProtocol.METHOD_ALERTS_DELIVER, AlertsWire.deliver(delivery))) {
            case CallOutcome.Answered answered -> new DeliverReply.Answered(
                    AlertsWire.deliverOutcomes(answered.body()));
            case CallOutcome.Refused refused -> refused.error().code().equals(BAD_REQUEST)
                    ? new DeliverReply.Rejected(refused.error().message())
                    : new DeliverReply.Unavailable(refused.error().code());
            case CallOutcome.NoAnswer none -> new DeliverReply.Unavailable(none.message());
        };
    }

    @Override
    public Optional<List<IntegrationState>> integrations() {
        return read(LauncherProtocol.METHOD_ALERTS_INTEGRATIONS_GET, AlertsWire::integrations);
    }

    @Override
    public IntegrationReply change(IntegrationChange change) {
        if (!isAvailable()) {
            return new IntegrationReply.Unavailable(unavailableMessage());
        }
        return switch (service.exchange(LauncherProtocol.METHOD_ALERTS_INTEGRATIONS_SET,
                AlertsWire.change(change))) {
            case CallOutcome.Answered answered -> AlertsWire.integration(answered.body())
                    .<IntegrationReply>map(IntegrationReply.Updated::new)
                    .orElseGet(() -> new IntegrationReply.Refused(Optional.empty(), NOT_SAVED));
            case CallOutcome.Refused refused -> refusal(refused.error());
            case CallOutcome.NoAnswer _ -> new IntegrationReply.Unavailable(LAUNCHER_NOT_CONNECTED);
        };
    }

    @Override
    public TestReply sendTest(AlertService target) {
        if (!isAvailable()) {
            return new TestReply.Unavailable(unavailableMessage());
        }
        return switch (service.exchange(LauncherProtocol.METHOD_ALERTS_INTEGRATIONS_TEST, AlertsWire.test(target))) {
            case CallOutcome.Answered answered -> AlertsWire.testDeliveryId(answered.body()).isPresent()
                    ? new TestReply.Queued(AlertsWire.testDeliveryId(answered.body()).getAsLong())
                    : new TestReply.Unavailable(NOT_SAVED);
            case CallOutcome.Refused refused -> refused.error().code().equals(BAD_REQUEST)
                    ? new TestReply.Refused(refused.error().message())
                    : new TestReply.Unavailable(wordedOrCode(refused.error()));
            case CallOutcome.NoAnswer _ -> new TestReply.Unavailable(LAUNCHER_NOT_CONNECTED);
        };
    }

    @Override
    public Optional<List<ServiceState>> status() {
        return read(LauncherProtocol.METHOD_ALERTS_STATUS, AlertsWire::status);
    }

    @Override
    public Runnable addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> listeners.remove(listener);
    }

    @Override
    public void close() {
        detach.forEach(Runnable::run);
        listeners.clear();
    }

    private <T> Optional<T> read(String method, Function<MsgValue, T> decode) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        return switch (service.exchange(method, AlertsWire.emptyRequest())) {
            case CallOutcome.Answered answered -> Optional.of(decode.apply(answered.body()));
            case CallOutcome.Refused refused -> {
                log.info("Alerts: {} was refused: {}", method, refused.error().code());
                yield Optional.empty();
            }
            case CallOutcome.NoAnswer _ -> Optional.empty();
        };
    }

    /** The reply for a refused change; package-private for the fixture test. */
    static IntegrationReply refusal(WireError error) {
        if (error.code().equals(UNKNOWN_METHOD)) {
            return new IntegrationReply.Unavailable(NEEDS_NEWER_LAUNCHER);
        }
        return new IntegrationReply.Refused(error.field(), wordedOrCode(error));
    }

    private static String wordedOrCode(WireError error) {
        return error.message().isBlank() ? NOT_SAVED + " (" + error.code() + ")" : error.message();
    }

    private String unavailableMessage() {
        return service.isConnected() ? NEEDS_NEWER_LAUNCHER : LAUNCHER_NOT_CONNECTED;
    }

    private void onRegistered() {
        if (isAvailable()) {
            tell(Listener::onAvailable);
        } else {
            log.info("The connected launcher does not send alerts; they wait until it is updated");
        }
    }

    private void onLauncherEvent(LauncherEvent event) {
        switch (event) {
            case LauncherEvent.ServiceLost _ -> tell(Listener::onUnavailable);
            default -> {
            }
        }
    }

    private void onResult(MsgValue body) {
        AlertsWire.result(body).ifPresent(result -> tell(listener -> listener.onResult(result)));
    }

    private void tell(Consumer<Listener> call) {
        for (Listener listener : listeners) {
            try {
                call.accept(listener);
            } catch (RuntimeException e) {
                log.warn("An alerts listener failed", e);
            }
        }
    }
}
