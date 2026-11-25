package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.runtime.SessionPacing;
import com.botwithus.bot.core.rpc.RpcException;
import com.botwithus.bot.core.rpc.RpcRemoteException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An older agent reads inactive, and costs one round-trip in total. */
class SessionPacingReaderTest {

    private static final int LOOPS = 5;
    private static final String METHOD_NOT_FOUND = "method not found: get_humanizer_session";

    @Test
    void read_agentPredatesTheMethod_isInactiveAndAskedOnlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        SessionPacingReader reader = new SessionPacingReader(() -> {
            calls.incrementAndGet();
            throw new RpcRemoteException(SessionPacingCodec.METHOD, METHOD_NOT_FOUND);
        });

        for (int i = 0; i < LOOPS; i++) {
            assertSame(SessionPacing.INACTIVE, reader.read());
        }
        assertEquals(1, calls.get());
    }

    @Test
    void read_otherRefusal_isInactiveForThatCallOnly() {
        AtomicInteger calls = new AtomicInteger();
        SessionPacingReader reader = new SessionPacingReader(() -> {
            calls.incrementAndGet();
            throw new RpcRemoteException(SessionPacingCodec.METHOD, "not in game");
        });

        assertSame(SessionPacing.INACTIVE, reader.read());
        assertSame(SessionPacing.INACTIVE, reader.read());

        assertEquals(2, calls.get());
    }

    @Test
    void read_activeReply_decodes() {
        SessionPacingReader reader = new SessionPacingReader(() -> Map.of("active", true));

        assertTrue(reader.read().isActive());
    }

    @Test
    void read_transportFailure_propagates() {
        SessionPacingReader reader = new SessionPacingReader(() -> {
            throw new RpcException("pipe closed");
        });

        assertThrows(RpcException.class, reader::read);
    }
}
