package com.botwithus.bot.core.impl;

import com.botwithus.bot.core.rpc.RpcClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * World map elements through {@link GameAPIImpl}. The query logic is
 * {@link WorldMapElementIndexTest}'s; the facade's post-filters are the api module's
 * {@code WorldMapElementsTest}. What is left here is the wiring: cache-backed, no RPC.
 */
class GameAPIImplWorldMapTest {

    private final RpcClient rpc = mock(RpcClient.class);
    private final GameAPIImpl api = new GameAPIImpl(rpc, null, () -> null);

    @Test
    void mapElementsFacadeIsSingleton() {
        assertSame(api.mapElements(), api.mapElements());
    }

    @Test
    void withNoCache_queriesAreEmpty_andNothingGoesOverThePipe() {
        assertEquals(0, api.mapElements().query().nearPlayer().count());
        assertEquals(0, api.queryWorldMapElements(Map.of()).size());

        verify(rpc, never()).callSyncList(anyString(), anyMap());
        verify(rpc, never()).callSync(anyString(), anyMap());
    }
}
