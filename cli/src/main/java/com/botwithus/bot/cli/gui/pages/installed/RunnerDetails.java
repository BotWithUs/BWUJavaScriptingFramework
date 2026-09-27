package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.config.ConfigField;
import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.gui.runners.RunnerReading;
import com.botwithus.bot.core.runtime.ScriptRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Reads one runner, and its client, into what the page shows: the facts its
 * state is derived from, and the line under the client's name.
 */
final class RunnerDetails {

    private static final Logger log = LoggerFactory.getLogger(RunnerDetails.class);
    private static final String SEP = " · ";

    private RunnerDetails() {}

    static RunnerFacts factsOf(Connection conn, ScriptRunner runner) {
        return new RunnerFacts(conn.isAlive(), RunnerReading.of(runner));
    }

    /** The account playing on {@code conn}, or its connection name while that is unknown. */
    static String clientName(Connection conn) {
        String account = conn.getAccountName();
        return account != null && !account.isBlank() ? account : conn.getName();
    }

    /** Why {@code conn} is not connected, in two words. */
    static String offlineNote(Connection conn) {
        ReconnectState state = conn.currentReconnectState();
        if (state == null) {
            return "not connected";
        }
        return switch (state) {
            case ReconnectState.Reconnecting ignored -> "reconnecting";
            case ReconnectState.GivingUp ignored -> "gave up reconnecting";
            case ReconnectState.Connected ignored -> "not connected";
            case ReconnectState.Disconnected ignored -> "not connected";
        };
    }

    /** The line under the client's name in the Clients tab. */
    static String detail(RunnerState state, RunnerFacts facts, ScriptRunner runner, Connection conn, Instant now) {
        return switch (state) {
            case RUNNING -> running(facts, runner, now);
            case STALLED -> "Stalled" + SEP + "inside onLoop()";
            case CRASHED -> "Crashed" + SEP + facts.currentCrash().map(RunnerDetails::crashSummary).orElse("error");
            case CUT_OFF -> "Cut off" + SEP + "would not stop";
            case STOPPED -> "Stopped";
            case OFFLINE -> "Waiting" + SEP + offlineNote(conn);
        };
    }

    private static String running(RunnerFacts facts, ScriptRunner runner, Instant now) {
        StringBuilder out = new StringBuilder("Running");
        facts.lastStartedAt().ifPresent(started ->
                out.append(SEP).append(WhenText.uptime(Duration.between(started, now))));
        double avg = runner.getProfiler().avgLoopMs();
        if (avg > 0) {
            out.append(SEP).append(Math.round(avg)).append(" ms");
        }
        return out.toString();
    }

    /** {@code "NullPointerException in onLoop()"}. */
    static String crashSummary(LastCrash crash) {
        String type = crash.cause() != null ? crash.cause().getClass().getSimpleName() : "Error";
        return type + " in " + phaseMethod(crash.phase());
    }

    private static String phaseMethod(Phase phase) {
        return switch (phase) {
            case ON_START -> "onStart()";
            case ON_LOOP -> "onLoop()";
            case ON_STOP -> "onStop()";
            case ON_CONFIG_UPDATE -> "onConfigUpdate()";
        };
    }

    /** How many settings fields {@code script} declares; script code, so a throw reads as none. */
    static int settingsCount(BotScript script) {
        try {
            List<ConfigField> fields = script.getConfigFields();
            return fields != null ? fields.size() : 0;
        } catch (RuntimeException e) {
            log.debug("getConfigFields() threw for {}: {}", script.getClass().getName(), e.toString());
            return 0;
        }
    }

    /** Whether {@code script} draws its own UI; script code, so a throw reads as no. */
    static boolean hasUi(BotScript script) {
        try {
            ScriptUI ui = script.getUI();
            return ui != null;
        } catch (RuntimeException e) {
            log.debug("getUI() threw for {}: {}", script.getClass().getName(), e.toString());
            return false;
        }
    }
}
