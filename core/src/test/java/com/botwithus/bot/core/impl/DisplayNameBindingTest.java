package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.debug.ScriptContextPublisher;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * The host binds a connection's name source once, on the base context; the runtime
 * then derives each script's context through the other withers. Every one of them
 * has to carry the source along, or a script would see no name.
 */
class DisplayNameBindingTest {

    private static final String NAME = "Zezima";

    private final AtomicReference<Optional<String>> current = new AtomicReference<>(Optional.empty());
    private final Supplier<Optional<String>> source = current::get;

    @Test
    void scriptContext_unbound_reportsNoName() {
        assertEquals(Optional.empty(), baseContext().getDisplayName());
    }

    @Test
    void scriptContext_everyWither_keepsTheBoundSourceLive() {
        ScriptContextImpl bound = baseContext().withDisplayName(source);
        List<ScriptContextImpl> derived = List.of(
                bound,
                bound.withScriptContext(ScriptContextPublisher.NOOP),
                bound.withEventBus(new EventBusImpl()),
                bound.withScriptMessageBus(new MessageBusImpl(), "script"),
                bound.withStopSignal(() -> true),
                bound.withStopCallback(() -> { }));

        current.set(Optional.of(NAME));

        assertAll(derived.stream().map(context ->
                () -> assertEquals(Optional.of(NAME), context.getDisplayName())));
    }

    @Test
    void client_reportsItsSource_andNoNameWithoutOne() {
        GameAPI api = mock(GameAPI.class);
        ClientImpl bound = new ClientImpl("pipe", api, new EventBusImpl(), () -> true, null, source);
        ClientImpl unbound = new ClientImpl("pipe", api, new EventBusImpl(), () -> true);

        current.set(Optional.of(NAME));

        assertEquals(Optional.of(NAME), bound.getDisplayName());
        assertEquals(Optional.empty(), unbound.getDisplayName());
    }

    private static ScriptContextImpl baseContext() {
        return new ScriptContextImpl(mock(GameAPI.class), new EventBusImpl(), new MessageBusImpl());
    }
}
