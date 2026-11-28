package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.runtime.SessionPacing;
import com.botwithus.bot.core.rpc.RpcRemoteException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Reads the agent's session plan for one client, once per call.
 *
 * <p>An agent that predates {@code get_humanizer_session} answers "method not found". That
 * is remembered for the life of this reader, so a script pacing every loop against an older
 * agent costs one round-trip in total rather than one per loop, and reads
 * {@link SessionPacing#INACTIVE}: host pacing then behaves exactly as it did before the
 * layer existed. Any other refusal also reads inactive, for that call only. A transport
 * failure propagates, as it does from every other call.</p>
 */
final class SessionPacingReader {

    private static final Logger log = LoggerFactory.getLogger(SessionPacingReader.class);

    private final Supplier<Map<String, Object>> call;
    private volatile boolean isMethodMissing;

    SessionPacingReader(Supplier<Map<String, Object>> call) {
        this.call = call;
    }

    SessionPacing read() {
        if (isMethodMissing) {
            return SessionPacing.INACTIVE;
        }
        try {
            return SessionPacingCodec.decode(call.get());
        } catch (RpcRemoteException e) {
            return refused(e.producerMessage());
        }
    }

    private SessionPacing refused(String producerMessage) {
        if (ComponentOpsRefusals.classify(producerMessage)
                == ComponentOpsRefusals.Reason.AGENT_PREDATES_METHOD) {
            isMethodMissing = true;
            log.info("Agent predates {}; host pacing stays as it was", SessionPacingCodec.METHOD);
        } else {
            log.debug("{} refused: {}", SessionPacingCodec.METHOD, producerMessage);
        }
        return SessionPacing.INACTIVE;
    }
}
