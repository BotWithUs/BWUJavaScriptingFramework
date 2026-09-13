package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;

import java.util.List;

/** The outcome of asking the launcher to deliver and install a set of scripts. */
public sealed interface SdnInstallResult {

    /** The scripts arrived and were loaded. They still have to be registered with a runtime. */
    record Installed(List<BotScript> scripts) implements SdnInstallResult {

        public Installed {
            scripts = List.copyOf(scripts);
        }
    }

    /** Nothing was selected to install. */
    record NothingSelected() implements SdnInstallResult {
    }

    /**
     * This host was not started with SDN delivery active, so the launcher has no
     * way to hand it a script. The launcher turns delivery on when it starts the
     * host itself.
     */
    record DeliveryDisabled() implements SdnInstallResult {
    }

    /** The launcher never delivered the scripts — usually because it is not running. */
    record CourierUnavailable() implements SdnInstallResult {
    }

    /** Anything else that went wrong while requesting or loading. */
    record Failed(String reason) implements SdnInstallResult {
    }
}
