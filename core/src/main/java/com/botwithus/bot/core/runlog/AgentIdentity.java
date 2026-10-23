package com.botwithus.bot.core.runlog;

/**
 * What the connected agent says about itself, for the {@code agent_build} and
 * {@code game_revision} header keys.
 *
 * <p>The host does not receive either value today, so every run writes
 * {@link #UNKNOWN}. This record is the seam a later change binds: the agent's
 * additive {@code rpc.agent_info} ({@code build_id}, {@code game_build}) fills it
 * through {@code ScriptRuntime.setAgentIdentity}, one call at the connection
 * setup site.</p>
 *
 * @param agentBuild   the agent's build id, or {@code unknown}
 * @param gameRevision the game client build such as {@code 950-1}, or {@code unknown}
 */
public record AgentIdentity(String agentBuild, String gameRevision) {

    public static final AgentIdentity UNKNOWN =
            new AgentIdentity(RunLogHeader.UNKNOWN, RunLogHeader.UNKNOWN);
}
