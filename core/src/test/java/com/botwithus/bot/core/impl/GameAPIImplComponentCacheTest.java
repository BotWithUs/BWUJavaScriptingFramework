package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.shm.Layout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the {@code (ifaceId, compId, ifaceVersion)}-keyed component cache
 * inside {@link GameAPIImpl}. The cache shape is "same version → cache hit;
 * version bump → cache miss". Producer-side hooks bump per-iface version
 * tokens whenever any component within an iface mutates; the consumer reads
 * the token from SHM via the {@link IntUnaryOperator} passed to the
 * GameAPIImpl constructor (production wiring is
 * {@code iface -> region.snapshot().ifaceVersion(iface)}; tests use a stub).
 */
class GameAPIImplComponentCacheTest {

    private RpcClient rpc;
    private Map<Integer, Integer> versions; // iface -> version (mutable in tests)
    private GameAPIImpl api;

    @BeforeEach
    void setUp() {
        rpc = mock(RpcClient.class);
        versions = new HashMap<>();
        IntUnaryOperator versionSource = iface -> versions.getOrDefault(iface, 0);
        api = new GameAPIImpl(rpc, null, versionSource);
    }

    private static Map<String, Object> rpcReply(int iface, int comp) {
        Map<String, Object> r = new HashMap<>();
        r.put("iface", iface);
        r.put("comp", comp);
        r.put("sub", 0);
        r.put("type", 8);
        r.put("x", 100); r.put("y", 200);
        r.put("w", 50);  r.put("h", 30);
        r.put("raw_x", 0); r.put("raw_y", 0);
        r.put("raw_w", 50); r.put("raw_h", 30);
        r.put("x_pos_mode", 0); r.put("y_pos_mode", 0);
        r.put("x_size_mode", 0); r.put("y_size_mode", 0);
        r.put("abs_screen_pos", 0);
        r.put("text", "hello");
        r.put("hidden", 0);
        r.put("sprite_id", -1);
        r.put("item_id", -1);
        r.put("item_amount", -1);
        return r;
    }

    @Test
    void firstCallHitsRpcAndPopulatesCache() {
        when(rpc.callSync(eq("get_component"), anyMap())).thenReturn(rpcReply(1477, 5));

        Component c = api.getComponent(1477, 5);
        assertNotNull(c);
        assertEquals(1477, c.ifaceId());
        assertEquals(5, c.compId());
        assertEquals(1, api.componentCacheSize());
    }

    @Test
    void secondCallSameVersionReturnsCacheWithoutRpc() {
        when(rpc.callSync(eq("get_component"), anyMap())).thenReturn(rpcReply(1477, 5));

        Component first = api.getComponent(1477, 5);
        Component second = api.getComponent(1477, 5);

        assertSame(first, second, "expected cached instance reuse on repeat call");
        verify(rpc, times(1)).callSync(eq("get_component"), anyMap());
    }

    @Test
    void versionBumpInvalidatesCache() {
        when(rpc.callSync(eq("get_component"), anyMap())).thenReturn(rpcReply(1477, 5));

        api.getComponent(1477, 5);
        versions.put(1477, 1); // producer bumps the token
        api.getComponent(1477, 5);

        verify(rpc, times(2)).callSync(eq("get_component"), anyMap());
    }

    @Test
    void differentIfacesAreIndependent() {
        when(rpc.callSync(eq("get_component"), anyMap()))
                .thenReturn(rpcReply(1477, 5))
                .thenReturn(rpcReply(1480, 5))
                .thenReturn(rpcReply(1477, 5));

        api.getComponent(1477, 5);
        api.getComponent(1480, 5);
        // Bumping 1480 must not evict the 1477 entry.
        versions.put(1480, 1);
        api.getComponent(1477, 5);
        api.getComponent(1480, 5);

        // 1477 stays cached after the call sequence; 1480 re-fetched once after the bump.
        // Total RPC calls: initial 1477 + initial 1480 + post-bump 1480 = 3.
        verify(rpc, times(3)).callSync(eq("get_component"), anyMap());
    }

    @Test
    void differentComponentsInSameIfaceCacheIndependently() {
        when(rpc.callSync(eq("get_component"), anyMap()))
                .thenReturn(rpcReply(1477, 5))
                .thenReturn(rpcReply(1477, 6))
                .thenReturn(rpcReply(1477, 5))
                .thenReturn(rpcReply(1477, 6));

        api.getComponent(1477, 5);
        api.getComponent(1477, 6);
        // Both should be cached; repeat reads hit cache.
        api.getComponent(1477, 5);
        api.getComponent(1477, 6);
        assertEquals(2, api.componentCacheSize());
        verify(rpc, times(2)).callSync(eq("get_component"), anyMap());
    }

    @Test
    void rpcReturningNotFoundEvictsStaleEntry() {
        // First call: iface=-1 means producer says "not found". Cache should
        // not store the negative result, and any prior stale entry should go.
        // Pre-populate the cache with a fake entry by doing one good call,
        // then bumping the version and re-calling with a not-found reply.
        Map<String, Object> notFound = new HashMap<>(rpcReply(1477, 99));
        notFound.put("iface", -1);

        when(rpc.callSync(eq("get_component"), anyMap()))
                .thenReturn(rpcReply(1477, 99))   // initial fetch
                .thenReturn(notFound);            // post-bump fetch returns not-found

        api.getComponent(1477, 99);
        assertEquals(1, api.componentCacheSize());

        versions.put(1477, 1);
        Component c = api.getComponent(1477, 99);
        assertNull(c);
        assertEquals(0, api.componentCacheSize(),
                "cache should drop the stale entry when RPC says not-found");
    }

    @Test
    void outOfRangeIfaceBypassesCache() {
        when(rpc.callSync(eq("get_component"), anyMap()))
                .thenReturn(rpcReply(Layout.IFACE_VERSION_CAP, 5));

        // ifaceId at the cap and beyond is uncacheable (no version slot),
        // so each call hits RPC. Note: the producer reply still uses
        // whatever iface id it returned; we just don't store it.
        api.getComponent(Layout.IFACE_VERSION_CAP, 5);
        api.getComponent(Layout.IFACE_VERSION_CAP, 5);
        verify(rpc, times(2)).callSync(eq("get_component"), anyMap());
        assertEquals(0, api.componentCacheSize());
    }

    @Test
    void negativeIfaceBypassesCache() {
        Map<String, Object> notFound = new HashMap<>(rpcReply(0, 0));
        notFound.put("iface", -1);
        when(rpc.callSync(eq("get_component"), anyMap())).thenReturn(notFound);

        // Negative iface goes to RPC which returns not-found; nothing cached.
        assertNull(api.getComponent(-1, 0));
        assertEquals(0, api.componentCacheSize());
    }

    @Test
    void cachingDisabledWhenNoVersionSource() {
        GameAPIImpl uncached = new GameAPIImpl(rpc); // legacy ctor, no version source
        when(rpc.callSync(eq("get_component"), anyMap())).thenReturn(rpcReply(1477, 5));

        uncached.getComponent(1477, 5);
        uncached.getComponent(1477, 5);
        verify(rpc, times(2)).callSync(eq("get_component"), anyMap());
        assertEquals(0, uncached.componentCacheSize());
    }

    @Test
    void clearComponentCacheDropsAllEntries() {
        when(rpc.callSync(eq("get_component"), anyMap()))
                .thenReturn(rpcReply(1477, 5))
                .thenReturn(rpcReply(1477, 6));

        api.getComponent(1477, 5);
        api.getComponent(1477, 6);
        assertEquals(2, api.componentCacheSize());

        api.clearComponentCache();
        assertEquals(0, api.componentCacheSize());
    }

    @Test
    void versionSourceCalledExactlyOncePerLookup() {
        AtomicInteger calls = new AtomicInteger(0);
        IntUnaryOperator counted = iface -> {
            calls.incrementAndGet();
            return versions.getOrDefault(iface, 0);
        };
        GameAPIImpl localApi = new GameAPIImpl(rpc, null, counted);
        when(rpc.callSync(eq("get_component"), anyMap())).thenReturn(rpcReply(1477, 5));

        localApi.getComponent(1477, 5);
        localApi.getComponent(1477, 5);
        assertEquals(2, calls.get(),
                "version source should be consulted on every getComponent call");
    }
}
