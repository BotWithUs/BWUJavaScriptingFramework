package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;

import java.util.List;
import java.util.Map;

/** The outcome of asking the launcher to deliver and install a set of scripts. */
public sealed interface SdnInstallResult {

    /**
     * The scripts arrived and were loaded. They still have to be registered with a runtime.
     *
     * @param scripts   every delivered script, in delivery order
     * @param byEntryId the scripts each requested catalogue entry was delivered as,
     *                  paired by the jar they came from; an entry is absent when its
     *                  jar yielded nothing or the delivery could not be paired
     */
    record Installed(List<BotScript> scripts, Map<String, List<BotScript>> byEntryId)
            implements SdnInstallResult {

        public Installed {
            scripts = List.copyOf(scripts);
            byEntryId = Map.copyOf(byEntryId);
        }

        /** The scripts delivered for catalogue entry {@code entryId}; empty when none were. */
        public List<BotScript> scriptsFor(String entryId) {
            return byEntryId.getOrDefault(entryId, List.of());
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
