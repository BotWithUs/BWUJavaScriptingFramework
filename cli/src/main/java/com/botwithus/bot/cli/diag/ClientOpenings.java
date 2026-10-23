package com.botwithus.bot.cli.diag;

import com.botwithus.bot.cli.events.HostEvent;

/** The host-bus test the Diagnostics switches share for "a client was just opened". */
final class ClientOpenings {

    private ClientOpenings() {
    }

    /** Whether {@code event} reports a newly opened client connection. */
    static boolean isClientOpened(HostEvent event) {
        return switch (event) {
            case HostEvent.ClientOpened _ -> true;
            default -> false;
        };
    }
}
