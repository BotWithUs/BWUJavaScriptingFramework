package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertDelivery;
import com.botwithus.bot.core.alerts.AlertGateway;
import com.botwithus.bot.core.alerts.AlertResult;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.DeliverReply;
import com.botwithus.bot.core.alerts.IntegrationChange;
import com.botwithus.bot.core.alerts.IntegrationReply;
import com.botwithus.bot.core.alerts.IntegrationState;
import com.botwithus.bot.core.alerts.ServiceState;
import com.botwithus.bot.core.alerts.TestReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The {@link AlertGateway} the alert machinery holds from the start, before the
 * host has registered with the launcher service. Until {@link #bind} is called
 * every call answers that no launcher is connected, so alerts wait rather than go
 * anywhere; after it, each call goes to the bound gateway. Listeners added here
 * keep hearing across the bind.
 */
public final class LauncherLink implements AlertGateway {

    private static final Logger log = LoggerFactory.getLogger(LauncherLink.class);

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Listener relay = new Relay();
    private volatile Optional<AlertGateway> bound = Optional.empty();
    private volatile Runnable unrelay = () -> { };

    /** Not bound yet: nothing is connected. */
    public LauncherLink() {
    }

    /**
     * From now on calls go to {@code gateway}. If it can already send alerts,
     * listeners hear {@link Listener#onAvailable()} at once.
     */
    public synchronized void bind(AlertGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        unrelay.run();
        bound = Optional.of(gateway);
        unrelay = gateway.addListener(relay);
        if (gateway.isAvailable()) {
            relay.onAvailable();
        }
    }

    @Override
    public boolean isAvailable() {
        return bound.map(AlertGateway::isAvailable).orElse(false);
    }

    @Override
    public boolean isConnected() {
        return bound.map(AlertGateway::isConnected).orElse(false);
    }

    @Override
    public DeliverReply deliver(AlertDelivery delivery) {
        return bound.map(gateway -> gateway.deliver(delivery))
                .orElseGet(() -> new DeliverReply.Unavailable(LAUNCHER_NOT_CONNECTED));
    }

    @Override
    public Optional<List<IntegrationState>> integrations() {
        return bound.flatMap(AlertGateway::integrations);
    }

    @Override
    public IntegrationReply change(IntegrationChange change) {
        return bound.map(gateway -> gateway.change(change))
                .orElseGet(() -> new IntegrationReply.Unavailable(LAUNCHER_NOT_CONNECTED));
    }

    @Override
    public TestReply sendTest(AlertService service) {
        return bound.map(gateway -> gateway.sendTest(service))
                .orElseGet(() -> new TestReply.Unavailable(LAUNCHER_NOT_CONNECTED));
    }

    @Override
    public Optional<List<ServiceState>> status() {
        return bound.flatMap(AlertGateway::status);
    }

    @Override
    public Runnable addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> listeners.remove(listener);
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

    /** Passes the bound gateway's news on to this link's listeners. */
    private final class Relay implements Listener {

        @Override
        public void onAvailable() {
            tell(Listener::onAvailable);
        }

        @Override
        public void onUnavailable() {
            tell(Listener::onUnavailable);
        }

        @Override
        public void onResult(AlertResult result) {
            tell(listener -> listener.onResult(result));
        }
    }
}
