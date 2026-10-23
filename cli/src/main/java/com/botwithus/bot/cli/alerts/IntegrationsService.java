package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertMessage;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.SendResult;
import com.botwithus.bot.core.alerts.WebhookUrl;
import com.botwithus.bot.core.secrets.CredentialStore;
import com.botwithus.bot.core.secrets.CredentialStoreException;
import com.botwithus.bot.core.secrets.Secret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Integrations} over the credential store, the notifier source and the
 * status board the {@link AlertDispatcher} also writes to.
 *
 * <p>Whether each service has a saved secret is read from the credential store
 * once and then tracked through {@link #saveSecret}, so {@link #services()} never
 * touches the store and is cheap enough for every frame.</p>
 */
public final class IntegrationsService implements Integrations {

    /** What Send test sends. */
    static final AlertMessage TEST_MESSAGE = new AlertMessage("Test from BotWithUs",
            "Alerts from this PC will arrive here.", false);

    private static final Logger log = LoggerFactory.getLogger(IntegrationsService.class);
    private static final String HTTPS_REQUIRED = "The webhook URL must start with https://";

    private final AlertSettings settings;
    private final CredentialStore credentials;
    private final boolean isPersistent;
    private final NotifierSource notifiers;
    private final ServiceStatusBoard status;
    private final DeliveryLanes lanes;
    private final Clock clock;
    private final ZoneId zone;
    private final Map<AlertService, Boolean> hasSecret = new ConcurrentHashMap<>();

    /**
     * @param isPersistent whether {@code credentials} survives a restart
     * @param lanes        where test sends run; the same lanes the dispatcher uses
     */
    public IntegrationsService(AlertSettings settings, CredentialStore credentials, boolean isPersistent,
                               NotifierSource notifiers, ServiceStatusBoard status, DeliveryLanes lanes,
                               Clock clock, ZoneId zone) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.isPersistent = isPersistent;
        this.notifiers = Objects.requireNonNull(notifiers, "notifiers");
        this.status = Objects.requireNonNull(status, "status");
        this.lanes = Objects.requireNonNull(lanes, "lanes");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override
    public List<ServiceView> services() {
        return Arrays.stream(AlertService.values()).map(this::service).toList();
    }

    @Override
    public ServiceView service(AlertService service) {
        boolean isEnabled = settings.isEnabled(service);
        return new ServiceView(service, isEnabled, hasSecret(service), status.status(service, isEnabled),
                status.last(service).map(send -> send.line(zone)));
    }

    @Override
    public Optional<Secret> readSecret(AlertService service) {
        try {
            return credentials.read(service.credentialTarget());
        } catch (CredentialStoreException e) {
            log.warn("Could not read the saved {} {}: {}", service.label(),
                    LiveNotifierSource.secretName(service), e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public SecretChange saveSecret(AlertService service, String value) {
        String text = value == null ? "" : value.strip();
        String name = LiveNotifierSource.secretName(service);
        try {
            if (text.isEmpty()) {
                credentials.delete(service.credentialTarget());
                hasSecret.put(service, false);
                return new SecretChange.Cleared();
            }
            if (text.length() > CredentialStore.MAX_SECRET_CHARS) {
                return new SecretChange.Refused("The " + name + " can be at most "
                        + CredentialStore.MAX_SECRET_CHARS + " characters");
            }
            Secret secret = new Secret(text);
            if (!service.isSecretOptional() && WebhookUrl.parseHttps(secret).isEmpty()) {
                return new SecretChange.Refused(HTTPS_REQUIRED);
            }
            credentials.write(service.credentialTarget(), secret);
            hasSecret.put(service, true);
            return new SecretChange.Saved();
        } catch (CredentialStoreException e) {
            String code = e.errorCode().isPresent() ? " (error " + e.errorCode().getAsInt() + ")" : "";
            return new SecretChange.Refused("Could not save the " + name + code);
        }
    }

    @Override
    public CompletableFuture<SendResult> sendTest(AlertService service) {
        CompletableFuture<SendResult> done = new CompletableFuture<>();
        status.markSending(service);
        lanes.submit(service, () -> {
            SendResult result = testResult(service);
            status.record(service, LastSend.test(result));
            done.complete(result);
        });
        return done;
    }

    @Override
    public boolean isPersistent() {
        return isPersistent;
    }

    private SendResult testResult(AlertService service) {
        try {
            return switch (notifiers.forService(service)) {
                case NotifierSetup.Ready ready -> ready.notifier().send(TEST_MESSAGE);
                case NotifierSetup.NotReady notReady -> notReady.asResult(clock.instant());
            };
        } catch (RuntimeException e) {
            log.warn("Send test to {} failed: {}", service.label(), e.getClass().getSimpleName());
            return new SendResult.Failed(clock.instant(), "Error", e.getClass().getSimpleName(), 0);
        }
    }

    private boolean hasSecret(AlertService service) {
        return hasSecret.computeIfAbsent(service, s -> readSecret(s).isPresent());
    }
}
