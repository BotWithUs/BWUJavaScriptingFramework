package com.botwithus.bot.core.shm;

import com.botwithus.bot.core.pipe.AgentPin;
import com.botwithus.bot.core.pipe.AgentSelection;

/**
 * Which game a developer probe reads: the pid on its command line, else the one
 * {@link AgentPin} allows. With several agents up and nothing named, a probe
 * stops and says so instead of picking the first, which could be someone's own
 * game.
 */
final class ProbeTarget {

    private static final int EXIT_NO_TARGET = 2;

    private ProbeTarget() {
    }

    /**
     * The pid to probe, or exits the JVM with status {@value #EXIT_NO_TARGET}
     * after printing why there is none.
     *
     * @param args  the probe's arguments; a first argument is the pid
     * @param usage the probe's usage line, printed with the reason
     */
    static long resolvePid(String[] args, String usage) {
        if (args.length >= 1) {
            return Long.parseLong(args[0]);
        }
        return switch (AgentPin.fromEnvironment()) {
            case AgentSelection.Attach attach -> attach.pid();
            case AgentSelection.NoAgent none -> exit(none.reason(), usage);
            case AgentSelection.Refused refused -> exit(refused.reason(), usage);
        };
    }

    private static long exit(String reason, String usage) {
        System.err.println(reason);
        System.err.println(usage);
        System.exit(EXIT_NO_TARGET);
        throw new IllegalStateException("System.exit returned");
    }
}
