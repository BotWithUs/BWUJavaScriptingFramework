package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.core.runtime.ScriptProfiler;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import java.util.List;

public class ProfileCommand implements Command {

    @Override public String name() { return "profile"; }
    @Override public List<String> aliases() { return List.of("prof"); }
    @Override public String description() { return "Show script execution profiling data"; }
    @Override public String usage() { return "profile [reset]"; }

    @Override
    public void execute(ParsedCommand parsed, CliContext ctx) {
        ScriptRuntime runtime = ctx.getRuntime();
        if (runtime == null) {
            ctx.out().println("No active connection.");
            return;
        }

        List<ScriptRunner> runners = runtime.getRunners();
        if (runners.isEmpty()) {
            ctx.out().println("No scripts loaded.");
            return;
        }

        if ("reset".equals(parsed.arg(0))) {
            for (ScriptRunner runner : runners) {
                runner.getProfiler().reset();
            }
            ctx.out().println("Profiling data reset.");
            return;
        }

        ctx.out().printf("%-25s %8s %10s %10s %10s %10s%n",
                "Script", "Loops", "Avg(ms)", "Min(ms)", "Max(ms)", "Last(ms)");
        ctx.out().println("-".repeat(80));
        for (ScriptRunner runner : runners) {
            ScriptProfiler p = runner.getProfiler();
            ctx.out().printf("%-25s %8d %10.2f %10.2f %10.2f %10.2f%n",
                    runner.getScriptName(),
                    p.getLoopCount(),
                    p.avgLoopMs(),
                    p.getMinLoopNanos() / 1_000_000.0,
                    p.getMaxLoopNanos() / 1_000_000.0,
                    p.getLastLoopNanos() / 1_000_000.0);
        }
    }
}
