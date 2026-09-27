package com.botwithus.bot.cli.gui.pages.dashboard;

import java.util.Objects;
import java.util.Optional;

/**
 * The four headline numbers above the tables, for the clients in scope.
 *
 * @param clientsAlive   clients whose pipe answers
 * @param clients        clients in scope
 * @param reconnecting   clients the host is retrying
 * @param worstAttempt   the highest retry attempt among them, 0 when none
 * @param running        runners looping
 * @param runners        runners registered
 * @param stalled        runners stalled
 * @param crashed        runners whose current run crashed
 * @param cutOff         runners cut off after ignoring a stop
 * @param stopped        runners stopped for any other reason
 * @param rpcCalls       RPC calls since the reset
 * @param rpcErrors      of those, the ones that failed
 * @param rpcCallsPerMin {@code rpcCalls} over the minutes since the reset
 * @param slowest        the running script with the slowest average loop, if any runs
 */
public record Kpis(int clientsAlive, int clients, int reconnecting, int worstAttempt,
                   int running, int runners, int stalled, int crashed, int cutOff, int stopped,
                   long rpcCalls, long rpcErrors, double rpcCallsPerMin, Optional<RunnerRow> slowest) {

    public Kpis {
        Objects.requireNonNull(slowest, "slowest");
    }
}
