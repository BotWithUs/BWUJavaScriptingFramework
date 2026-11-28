package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.runtime.SessionPacing;
import com.botwithus.bot.core.rpc.RpcException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pacing never takes a script down: a failed read paces as without the layer. */
class SessionPacingSourceTest {

    private static final long PRESS_IN = 700L;

    @Test
    void get_noGameApi_isInactive() {
        assertSame(SessionPacing.INACTIVE, new SessionPacingSource(() -> null).get());
    }

    @Test
    void get_transportFailure_isInactive() {
        GameAPI api = mock(GameAPI.class);
        when(api.sessionPacing()).thenThrow(new RpcException("pipe closed"));

        assertSame(SessionPacing.INACTIVE, new SessionPacingSource(() -> api).get());
    }

    @Test
    void get_activePlan_isPassedThrough() {
        GameAPI api = mock(GameAPI.class);
        SessionPacing plan = new SessionPacing(true, PRESS_IN, Optional.empty());
        when(api.sessionPacing()).thenReturn(plan);

        assertEquals(plan, new SessionPacingSource(() -> api).get());
    }
}
