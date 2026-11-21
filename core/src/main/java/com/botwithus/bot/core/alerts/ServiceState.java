package com.botwithus.bot.core.alerts;

import java.util.Objects;
import java.util.Optional;

/**
 * One service's sending state on the launcher, from {@code alerts.status}.
 *
 * @param service      the service
 * @param isConfigured whether it can be sent to at all
 * @param isSending    whether a send is in progress
 * @param queued       how many messages wait in its queue
 * @param last         how its most recent send ended, since the launcher started
 */
public record ServiceState(AlertService service, boolean isConfigured, boolean isSending, int queued,
                           Optional<AlertResult> last) {

    public ServiceState {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(last, "last");
    }
}
