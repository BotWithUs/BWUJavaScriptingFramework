package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** The outcome of asking the launcher to deliver and install a set of scripts. */
public sealed interface SdnInstallResult {

    /**
     * One requested script that was not installed while the rest were.
     *
     * @param entryId the catalogue id, when known
     * @param reason  the launcher's reason code, when it gave one
     * @param text    what to tell the user, naming the script
     */
    record ScriptRefusal(Optional<String> entryId, Optional<String> reason, String text) {

        public ScriptRefusal {
            Objects.requireNonNull(entryId, "entryId");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * The scripts arrived and were loaded. They still have to be registered with a runtime.
     *
     * @param scripts   every delivered script, in delivery order
     * @param byEntryId the scripts each requested catalogue entry was delivered as,
     *                  paired by the jar they came from; an entry is absent when its
     *                  jar yielded nothing or the delivery could not be paired
     * @param refused   the requested scripts that were not installed while the rest were
     */
    record Installed(List<BotScript> scripts, Map<String, List<BotScript>> byEntryId,
                     List<ScriptRefusal> refused) implements SdnInstallResult {

        public Installed {
            scripts = List.copyOf(scripts);
            byEntryId = Map.copyOf(byEntryId);
            refused = List.copyOf(refused);
        }

        /** Everything that was asked for was installed. */
        public Installed(List<BotScript> scripts, Map<String, List<BotScript>> byEntryId) {
            this(scripts, byEntryId, List.of());
        }

        /** The scripts delivered for catalogue entry {@code entryId}; empty when none were. */
        public List<BotScript> scriptsFor(String entryId) {
            return byEntryId.getOrDefault(entryId, List.of());
        }

        /** Why {@code entryId} was not installed, when the rest were but not that. */
        public Optional<ScriptRefusal> refusalFor(String entryId) {
            return refused.stream().filter(r -> r.entryId().filter(entryId::equals).isPresent()).findFirst();
        }

        /** The same install with {@code more} refusals added. */
        public Installed withRefused(List<ScriptRefusal> more) {
            List<ScriptRefusal> all = new ArrayList<>(refused);
            all.addAll(more);
            return new Installed(scripts, byEntryId, all);
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
