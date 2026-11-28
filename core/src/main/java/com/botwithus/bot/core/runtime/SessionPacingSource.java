package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.runtime.SessionPacing;
import com.botwithus.bot.core.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The plan source a script run binds to {@link SessionPacing#CURRENT}: reads the agent's
 * session plan from the script's own client each time a pacing helper asks.
 *
 * <p>Pacing must never be what takes a script down, so a failed read answers
 * {@link SessionPacing#INACTIVE} (the helpers then pace exactly as they did before the
 * layer existed) and logs one WARN per run. A dead pipe still surfaces on the script's next
 * real call.</p>
 */
final class SessionPacingSource implements Supplier<SessionPacing> {

    private static final Logger log = LoggerFactory.getLogger(SessionPacingSource.class);

    private final Supplier<GameAPI> api;
    private final AtomicBoolean hasWarned = new AtomicBoolean();

    SessionPacingSource(Supplier<GameAPI> api) {
        this.api = api;
    }

    @Override
    public SessionPacing get() {
        GameAPI gameAPI = api.get();
        if (gameAPI == null) {
            return SessionPacing.INACTIVE;
        }
        try {
            SessionPacing pacing = gameAPI.sessionPacing();
            return pacing == null ? SessionPacing.INACTIVE : pacing;
        } catch (RpcException e) {
            if (hasWarned.compareAndSet(false, true)) {
                log.warn("Could not read the agent's session plan; pacing as without it: {}",
                        e.getMessage());
            }
            return SessionPacing.INACTIVE;
        }
    }
}
