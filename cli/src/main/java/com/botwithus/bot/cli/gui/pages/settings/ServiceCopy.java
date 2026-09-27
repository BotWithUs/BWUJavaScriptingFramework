package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.core.alerts.AlertService;

/**
 * The words on each Integrations card: what the service is, where to find what
 * it asks for, and the example shown in an empty box. Every URL here is a
 * placeholder that ends in an ellipsis; none is a working address.
 */
final class ServiceCopy {

    static final String NTFY_SERVER_PLACEHOLDER = "https://ntfy.sh";
    static final String NTFY_TOPIC_PLACEHOLDER = "a long name only you know";
    static final String MENTION_LABEL = "Mention @here on errors";

    private ServiceCopy() {
    }

    /** One line under the service's name. */
    static String description(AlertService service) {
        return switch (service) {
            case NTFY -> "Push notifications to your phone through ntfy.sh or your own server.";
            case SLACK -> "Post to a channel through an incoming webhook.";
            case DISCORD -> "Post to a channel through a server webhook.";
        };
    }

    /** Where to get what the fields ask for. */
    static String hint(AlertService service) {
        return switch (service) {
            case NTFY -> "Subscribe to this topic in the ntfy app. Anyone who knows the topic name can read it, "
                    + "so keep it hard to guess.";
            case SLACK -> "In Slack: Apps › Incoming Webhooks › Add to a channel, then paste the URL.";
            case DISCORD -> "In Discord: Channel settings › Integrations › Webhooks › Copy URL.";
        };
    }

    /** The example in the secret's box while nothing is saved or typed. */
    static String secretPlaceholder(AlertService service) {
        return switch (service) {
            case NTFY -> "tk_…";
            case SLACK -> "https://hooks.slack.com/services/…";
            case DISCORD -> "https://discord.com/api/webhooks/…";
        };
    }
}
