package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.ClientProvider;
import com.botwithus.bot.api.isc.MessageBus;
import com.botwithus.bot.api.isc.SharedState;
import com.botwithus.bot.api.script.ClientOrchestrator;
import com.botwithus.bot.api.script.ManagementTarget;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagementContextImplTest {

    @Test
    void returnsInjectedOrchestrator() {
        ClientOrchestrator orchestrator = mock(ClientOrchestrator.class);
        ManagementContextImpl ctx = new ManagementContextImpl(
                orchestrator, mock(ClientProvider.class),
                mock(MessageBus.class), mock(SharedState.class));

        assertSame(orchestrator, ctx.getOrchestrator());
    }

    @Test
    void returnsInjectedClientProvider() {
        ClientProvider provider = mock(ClientProvider.class);
        ManagementContextImpl ctx = new ManagementContextImpl(
                mock(ClientOrchestrator.class), provider,
                mock(MessageBus.class), mock(SharedState.class));

        assertSame(provider, ctx.getClientProvider());
    }

    @Test
    void returnsInjectedMessageBus() {
        MessageBus bus = mock(MessageBus.class);
        ManagementContextImpl ctx = new ManagementContextImpl(
                mock(ClientOrchestrator.class), mock(ClientProvider.class),
                bus, mock(SharedState.class));

        assertSame(bus, ctx.getMessageBus());
    }

    @Test
    void returnsInjectedSharedState() {
        SharedState state = mock(SharedState.class);
        ManagementContextImpl ctx = new ManagementContextImpl(
                mock(ClientOrchestrator.class), mock(ClientProvider.class),
                mock(MessageBus.class), state);

        assertSame(state, ctx.getSharedState());
    }

    @Test
    void targets_areReadFromTheSupplierOnEveryCall() {
        AtomicReference<Set<ManagementTarget>> current = new AtomicReference<>(Set.of());
        ManagementContextImpl ctx = new ManagementContextImpl(
                mock(ClientOrchestrator.class), mock(ClientProvider.class),
                mock(MessageBus.class), mock(SharedState.class), current::get);
        assertEquals(Set.of(), ctx.targets());

        Set<ManagementTarget> changed = Set.of(new ManagementTarget.ClientScript("uuid", "Woodcutting"));
        current.set(changed);

        assertEquals(changed, ctx.targets());
    }

    @Test
    void withoutATargetSupplier_theScriptManagesTheWholeHost() {
        ManagementContextImpl ctx = new ManagementContextImpl(
                mock(ClientOrchestrator.class), mock(ClientProvider.class),
                mock(MessageBus.class), mock(SharedState.class));

        assertEquals(Set.of(new ManagementTarget.WholeHost()), ctx.targets());
    }
}
