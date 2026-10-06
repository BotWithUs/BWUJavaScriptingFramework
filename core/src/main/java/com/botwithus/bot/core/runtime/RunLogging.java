package com.botwithus.bot.core.runtime;

import com.botwithus.bot.core.runlog.AgentIdentity;
import com.botwithus.bot.core.runlog.HostIdentity;
import com.botwithus.bot.core.runlog.KnownNames;
import com.botwithus.bot.core.runlog.RunLogs;

import java.time.Clock;
import java.util.function.Supplier;

/**
 * What a {@link ScriptRunner} needs to open a run log, as its connection knows
 * it at the moment a run starts.
 *
 * @param logs  the process's run logs
 * @param names live view of the names to redact for runs on this connection
 * @param slot  the connection's integer slot; written in the header in place of its name
 * @param agent what the agent reports about itself; {@link AgentIdentity#UNKNOWN} until bound
 */
public record RunLogging(RunLogs logs, Supplier<KnownNames> names, int slot,
                         Supplier<AgentIdentity> agent) {

    /** Slot written when the runtime was never given one. */
    public static final int NO_SLOT = 0;

    public RunLogging {
        if (logs == null) {
            throw new IllegalArgumentException("logs");
        }
        names = names != null ? names : () -> KnownNames.NONE;
        agent = agent != null ? agent : () -> AgentIdentity.UNKNOWN;
    }

    /**
     * For a runner or runtime never given run logs (test seams, headless
     * wiring): crash summaries and breadcrumbs are still kept, no file is written.
     */
    public static RunLogging fileLess() {
        return new RunLogging(RunLogs.withoutFiles(HostIdentity.current(RunLogging.class),
                Clock.systemUTC()), null, NO_SLOT, null);
    }
}
