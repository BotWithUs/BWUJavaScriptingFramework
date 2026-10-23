package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Liveness;
import com.botwithus.bot.core.runtime.ScriptRunner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The scripts the detail pane lists for a client: the ones running or crashed
 * on its connection, then the ones its account is set to start, which are shown
 * without a status. Every loaded script has a runner on every connection, so a
 * runner that is neither running nor crashed is not worth a chip.
 */
final class ClientScripts {

    private ClientScripts() {
    }

    /**
     * @param runners  the connection's runners; empty once it has closed
     * @param autoStart the account's auto-start list, in its order
     */
    static List<ScriptChip> of(List<ScriptRunner> runners, List<String> autoStart) {
        List<ScriptChip> chips = new ArrayList<>();
        Set<String> named = new LinkedHashSet<>();
        for (ScriptRunner runner : runners) {
            toneOf(runner).ifPresent(tone -> {
                if (named.add(runner.getScriptName())) {
                    chips.add(new ScriptChip(runner.getScriptName(), tone));
                }
            });
        }
        for (String script : autoStart) {
            if (named.add(script)) {
                chips.add(new ScriptChip(script, Tone.NEUTRAL));
            }
        }
        return List.copyOf(chips);
    }

    /** Running, stalled or crashed; empty for a runner doing none of those. */
    private static Optional<Tone> toneOf(ScriptRunner runner) {
        if (runner.isRunning()) {
            return Optional.of(runner.liveness() == Liveness.STALLED ? Tone.WARN : Tone.OK);
        }
        Optional<LastCrash> crash = runner.health().lastCrash();
        Instant started = runner.lastStartedAt();
        boolean isCrashed = crash.isPresent() && started != null && !crash.get().when().isBefore(started);
        return isCrashed ? Optional.of(Tone.ERROR) : Optional.empty();
    }
}
