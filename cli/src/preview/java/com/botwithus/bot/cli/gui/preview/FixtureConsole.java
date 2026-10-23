package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.cli.gui.AnsiOutputBuffer;
import com.botwithus.bot.cli.gui.pages.dashboard.EventRow;
import com.botwithus.bot.cli.log.LogEntry;
import com.botwithus.bot.cli.output.AnsiCodes;

import java.io.PrintStream;
import java.time.Instant;
import java.util.List;

/**
 * DEV ONLY. The dock's sample content for the preview: console output, log
 * lines, host events and two stack traces. Made-up sample data; the command
 * names, RPC methods and event kinds are the host's real ones.
 */
final class FixtureConsole {

    static final String CRASH_TRACE = """
            java.lang.NullPointerException: Cannot invoke "SceneObject.interact(String)" because "range" is null
            \tat com.example.quests.cooksassistant.CooksAssistant.useRange(CooksAssistant.java:143)
            \tat com.example.quests.cooksassistant.CooksAssistant.onLoop(CooksAssistant.java:61)
            \tat com.botwithus.bot.core.runtime.ScriptRunner.run(ScriptRunner.java:474)
            \tat java.base/java.lang.Thread.run(Thread.java:1583)
            """;

    static final String LOAD_TRACE = """
            java.util.ServiceConfigurationError: com.botwithus.bot.api.BotScript: Provider not found
            \tat java.base/java.util.ServiceLoader.fail(ServiceLoader.java:593)
            \tat com.botwithus.bot.core.runtime.LocalScriptLoader.load(LocalScriptLoader.java:118)
            """;

    private FixtureConsole() {}

    static NullPointerException sampleNpe() {
        return new NullPointerException("Cannot invoke \"SceneObject.interact(String)\" because \"range\" is null");
    }

    static void fill(AnsiOutputBuffer console, boolean isBusy) {
        PrintStream out = console.getPrintStream();
        out.println(AnsiCodes.colorize("BotWithUs Script Manager", AnsiCodes.CYAN));
        out.println(AnsiCodes.dim("Type 'help' for available commands."));
        if (!isBusy) {
            return;
        }
        out.println("[AutoStart] Background pipe scanning started.");
        out.println(AnsiCodes.colorize("[AutoStart] Started 1 script(s) for Oakheart.", AnsiCodes.GREEN));
        out.println(AnsiCodes.colorize("> metrics", AnsiCodes.YELLOW));
        out.println("query_entities   18420 calls   avg 3.1 ms   p95 7.8 ms");
        out.println("get_varp         12960 calls   avg 0.9 ms   p95 1.9 ms");
        out.println(AnsiCodes.colorize("> scripts", AnsiCodes.YELLOW));
        out.println(AnsiCodes.colorize("RUNNING  Woodcutting v2.0", AnsiCodes.GREEN));
        out.println(AnsiCodes.colorize("STOPPED  Example Script v1.0", AnsiCodes.WHITE));
        console.insertProgress("Capturing BotWithUs_14208");
    }

    static List<LogEntry> logs(Instant now) {
        return List.of(
                new LogEntry(now.minusSeconds(370), "AutoStart", "INFO", "Started 1 script(s) for Oakheart.",
                        "BotWithUs_14208"),
                new LogEntry(now.minusSeconds(366), "Connection", "INFO", "Connected to BotWithUs_9932 (world 2).",
                        "BotWithUs_9932"),
                new LogEntry(now.minusSeconds(245), "ScriptRunner", "ERROR",
                        "Cook's Assistant crashed in onLoop: NullPointerException", "BotWithUs_15002"),
                new LogEntry(now.minusSeconds(140), "ScriptLoader", "ERROR",
                        "woodcutting-1.0-SNAPSHOT.jar: ServiceConfigurationError", null),
                new LogEntry(now.minusSeconds(139), "ScriptLoader", "INFO", "Loaded 8 JAR(s), 1 failed.", null),
                new LogEntry(now.minusSeconds(120), "RpcClient", "DEBUG", "find_world_path took 188 ms (p99)",
                        "BotWithUs_17012"),
                new LogEntry(now.minusSeconds(84), "Reconnect", "WARN",
                        "Reconnecting BotWithUs_10344: attempt 1 in 500ms", "BotWithUs_10344"),
                new LogEntry(now.minusSeconds(60), "Watchdog", "WARN", "Divination STALLED: 30 s inside onLoop()",
                        "BotWithUs_9932"),
                new LogEntry(now.minusSeconds(42), "Reconnect", "WARN",
                        "Reconnecting BotWithUs_10344: attempt 4 in 8000ms", "BotWithUs_10344"));
    }

    static List<EventRow> events(Instant now) {
        return List.of(
                new EventRow(now.minusSeconds(370), "ClientOpened", "Oakheart", "Pipe BotWithUs_14208 connected"),
                new EventRow(now.minusSeconds(368), "ScriptStarted", "Oakheart", "Woodcutting"),
                new EventRow(now.minusSeconds(245), "ScriptCrashed", "Tamsin Vale",
                        "Cook's Assistant · onLoop · NullPointerException"),
                new EventRow(now.minusSeconds(140), "ScriptLoadFailed", "-",
                        "woodcutting-1.0-SNAPSHOT.jar · ServiceConfigurationError: provider not found"),
                new EventRow(now.minusSeconds(84), "ConnectionLost", "Hollowmere", "Pipe closed"),
                new EventRow(now.minusSeconds(84), "Reconnect.Reconnecting", "Hollowmere", "attempt 1 · next 500 ms"),
                new EventRow(now.minusSeconds(60), "ScriptStalled", "Fernmoss",
                        "Divination · inside onLoop() past the stall limit"),
                new EventRow(now.minusSeconds(42), "Reconnect.Reconnecting", "Hollowmere",
                        "attempt 4 · next 8000 ms"));
    }
}
