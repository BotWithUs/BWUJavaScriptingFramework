package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.core.alerts.AlertGateway;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.IntegrationChange;
import com.botwithus.bot.core.alerts.IntegrationReply;
import com.botwithus.bot.core.alerts.IntegrationState;
import com.botwithus.bot.core.secrets.CredentialStore;
import com.botwithus.bot.core.secrets.CredentialStoreException;
import com.botwithus.bot.core.secrets.Secret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Moves the set-up this host kept before the launcher took alerts over: each
 * service's secret from the host's own credential entry
 * ({@link AlertService#credentialTarget()}), with the ntfy server and topic and
 * Discord's {@code @here} from the settings file.
 *
 * <p>Run each time a launcher that sends alerts connects; once a service has moved
 * there is nothing left to move, so a later run does nothing. Per service:</p>
 * <ul>
 *   <li>the host holds nothing: nothing happens;</li>
 *   <li>the launcher already has the service set up: the launcher's copy wins and
 *       is left alone, and the host's copy is deleted;</li>
 *   <li>otherwise the host's copy is sent with {@code alerts.integrations.set} and,
 *       once the launcher has saved it, deleted. A refusal or no answer keeps it,
 *       for the next run.</li>
 * </ul>
 *
 * <p>"Deleted" means exactly the host's own entry for that service, and the three
 * settings reset to their defaults so they are never read again. No other
 * credential entry is read, written or deleted.</p>
 */
public final class IntegrationMigration {

    /** What one run did for one service. */
    enum Outcome {
        /** The host held nothing for the service. */
        NOTHING_TO_MOVE,
        /** The launcher saved the host's copy; the host's copy is gone. */
        MOVED,
        /** The launcher had its own; it was left alone and the host's copy is gone. */
        LAUNCHER_HAD_ITS_OWN,
        /** The launcher refused it or did not answer; the host's copy stays for the next run. */
        KEPT_FOR_LATER
    }

    /** One service's outcome. */
    record Step(AlertService service, Outcome outcome) { }

    private static final Logger log = LoggerFactory.getLogger(IntegrationMigration.class);

    /** What the host held for one service. */
    private record HostCopy(Optional<Secret> secret, String server, String topic, boolean isMentioningHere) {

        boolean isEmpty(AlertService service) {
            return secret.isEmpty() && (service != AlertService.NTFY || topic.isBlank());
        }
    }

    private final AlertGateway gateway;
    private final CredentialStore credentials;
    private final HostSettings settings;

    /**
     * @param credentials the host's own credential store; only each service's own entry is touched
     * @param settings    the host's settings, holding the old ntfy and Discord fields
     */
    public IntegrationMigration(AlertGateway gateway, CredentialStore credentials, HostSettings settings) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Moves what there is to move; empty when the launcher's set-up could not be read. */
    List<Step> run() {
        Optional<List<IntegrationState>> launcher = gateway.integrations();
        if (launcher.isEmpty()) {
            return List.of();
        }
        List<Step> steps = new ArrayList<>();
        for (IntegrationState state : launcher.get()) {
            hostCopy(state.service()).ifPresent(copy -> steps.add(new Step(state.service(), move(state, copy))));
        }
        return List.copyOf(steps);
    }

    private Outcome move(IntegrationState launcher, HostCopy copy) {
        AlertService service = launcher.service();
        if (copy.isEmpty(service)) {
            return Outcome.NOTHING_TO_MOVE;
        }
        if (isSetUp(launcher)) {
            forget(service);
            log.info("Alerts: the launcher already had {} set up; kept its copy and removed the host's",
                    service.label());
            return Outcome.LAUNCHER_HAD_ITS_OWN;
        }
        return switch (gateway.change(changeOf(service, copy))) {
            case IntegrationReply.Updated _ -> {
                forget(service);
                log.info("Alerts: moved the {} set-up to the launcher", service.label());
                yield Outcome.MOVED;
            }
            case IntegrationReply.Refused refused -> {
                log.warn("Alerts: the launcher refused the saved {} set-up ({}); kept it for now",
                        service.label(), refused.message());
                yield Outcome.KEPT_FOR_LATER;
            }
            case IntegrationReply.Unavailable _ -> Outcome.KEPT_FOR_LATER;
        };
    }

    /** Whether the launcher's copy counts as set up, so it wins over the host's. */
    private static boolean isSetUp(IntegrationState launcher) {
        return launcher.hasSecret() || (launcher.service() == AlertService.NTFY && !launcher.topic().isEmpty());
    }

    private static IntegrationChange changeOf(AlertService service, HostCopy copy) {
        return new IntegrationChange(service, copy.secret(), false,
                service == AlertService.DISCORD ? Optional.of(copy.isMentioningHere()) : Optional.empty(),
                service == AlertService.NTFY ? Optional.of(copy.server()) : Optional.empty(),
                service == AlertService.NTFY ? Optional.of(copy.topic()) : Optional.empty());
    }

    /** The host's copy; empty when its credential entry could not be read, so nothing is decided. */
    private Optional<HostCopy> hostCopy(AlertService service) {
        try {
            return Optional.of(new HostCopy(credentials.read(service.credentialTarget()),
                    settings.get(AlertSettingKeys.NTFY_SERVER).strip(),
                    settings.get(AlertSettingKeys.NTFY_TOPIC).strip(),
                    settings.get(AlertSettingKeys.DISCORD_MENTION_HERE)));
        } catch (CredentialStoreException e) {
            log.warn("Alerts: could not read the host's saved {} set-up; will try again: {}", service.label(),
                    e.getMessage());
            return Optional.empty();
        }
    }

    /** Deletes the host's own entry for {@code service}, and nothing else, and resets its settings. */
    private void forget(AlertService service) {
        try {
            credentials.delete(service.credentialTarget());
        } catch (CredentialStoreException e) {
            log.warn("Alerts: could not delete the host's old {} entry: {}", service.label(), e.getMessage());
        }
        switch (service) {
            case NTFY -> {
                settings.reset(AlertSettingKeys.NTFY_SERVER);
                settings.reset(AlertSettingKeys.NTFY_TOPIC);
            }
            case DISCORD -> settings.reset(AlertSettingKeys.DISCORD_MENTION_HERE);
            case SLACK -> {
            }
        }
    }
}
