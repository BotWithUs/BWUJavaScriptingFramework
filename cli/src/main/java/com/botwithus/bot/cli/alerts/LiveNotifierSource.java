package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.DiscordNotifier;
import com.botwithus.bot.core.alerts.HttpDelivery;
import com.botwithus.bot.core.alerts.Notifier;
import com.botwithus.bot.core.alerts.NtfyNotifier;
import com.botwithus.bot.core.alerts.SlackNotifier;
import com.botwithus.bot.core.secrets.CredentialStore;
import com.botwithus.bot.core.secrets.CredentialStoreException;
import com.botwithus.bot.core.secrets.Secret;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link NotifierSource} over the {@code alerts.*} settings and the credential
 * store. Every reason it gives is safe to show: none repeats a URL or a token.
 */
public final class LiveNotifierSource implements NotifierSource {

    private final AlertSettings settings;
    private final CredentialStore credentials;
    private final HttpDelivery delivery;

    public LiveNotifierSource(AlertSettings settings, CredentialStore credentials, HttpDelivery delivery) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    @Override
    public NotifierSetup forService(AlertService service) {
        try {
            return switch (service) {
                case NTFY -> ntfy();
                case SLACK -> webhook(service, hook -> new SlackNotifier(hook, delivery));
                case DISCORD -> webhook(service,
                        hook -> new DiscordNotifier(hook, settings.isMentioningHere(), delivery));
            };
        } catch (CredentialStoreException e) {
            String code = e.errorCode().isPresent() ? " (error " + e.errorCode().getAsInt() + ")" : "";
            return new NotifierSetup.NotReady("Could not read the saved " + secretName(service) + code);
        } catch (IllegalArgumentException e) {
            return new NotifierSetup.NotReady(e.getMessage());
        }
    }

    private NotifierSetup ntfy() {
        String topic = settings.ntfyTopic();
        if (topic.isBlank()) {
            return new NotifierSetup.NotReady("Add the topic first");
        }
        Optional<URI> server = parse(settings.ntfyServer());
        if (server.isEmpty()) {
            return new NotifierSetup.NotReady("The server must be an http:// or https:// URL");
        }
        Optional<Secret> token = credentials.read(AlertService.NTFY.credentialTarget());
        return new NotifierSetup.Ready(new NtfyNotifier(server.get(), topic, token, delivery));
    }

    private NotifierSetup webhook(AlertService service, Function<Secret, Notifier> build) {
        Optional<Secret> hook = credentials.read(service.credentialTarget());
        if (hook.isEmpty()) {
            return new NotifierSetup.NotReady("Add the " + secretName(service) + " first");
        }
        return new NotifierSetup.Ready(build.apply(hook.get()));
    }

    /** The secret's label for use mid-sentence: "webhook URL", "access token". */
    static String secretName(AlertService service) {
        String label = service.secretLabel();
        return label.substring(0, 1).toLowerCase(Locale.ROOT) + label.substring(1);
    }

    private static Optional<URI> parse(String text) {
        try {
            return Optional.of(new URI(text.strip()));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
