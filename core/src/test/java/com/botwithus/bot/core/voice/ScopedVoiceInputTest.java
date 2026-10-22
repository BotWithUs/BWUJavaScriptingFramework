package com.botwithus.bot.core.voice;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.voice.Heard;
import com.botwithus.bot.api.voice.VoiceInput;
import com.botwithus.bot.api.voice.VoiceSession;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.MessageBusImpl;
import com.botwithus.bot.core.impl.ScriptContextImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ScopedVoiceInputTest {

    private final CountingVoiceInput host = new CountingVoiceInput();
    private final ScopedVoiceInput scoped = new ScopedVoiceInput(host);

    @Test
    void listenAgain_closesThePreviousSession_andCloseAllTheLatest() {
        scoped.listen(List.of("one"), h -> { });
        scoped.listen(List.of("two"), h -> { });
        assertEquals(1, host.closes.get());

        scoped.closeAll();
        assertEquals(2, host.closes.get());

        scoped.closeAll();
        assertEquals(2, host.closes.get());
    }

    @Test
    void closeAll_skipsSessionsTheScriptAlreadyClosed() {
        VoiceSession session = scoped.listen(List.of("one"), h -> { });
        session.close();

        scoped.closeAll();

        assertEquals(1, host.closes.get());
    }

    @Test
    void available_isTheHosts() {
        assertFalse(scoped.available());
        host.isAvailable = true;
        assertTrue(scoped.available());
    }

    @Test
    void scriptContext_defaultsToUnavailable_andWithVoiceInputSurvivesOtherWithers() {
        ScriptContextImpl bare = new ScriptContextImpl(mock(GameAPI.class), new EventBusImpl(), new MessageBusImpl());
        assertSame(VoiceInput.UNAVAILABLE, bare.getVoiceInput());

        ScriptContextImpl bound = bare.withVoiceInput(scoped).withStopSignal(() -> true).withDisplayName(null);

        assertSame(scoped, bound.getVoiceInput());
    }

    @Test
    void unavailable_neverHears_andRejectsNulls() {
        assertFalse(VoiceInput.UNAVAILABLE.available());
        VoiceInput.UNAVAILABLE.listen(List.of("x"), h -> { }).close();
        assertThrows(NullPointerException.class, () -> VoiceInput.UNAVAILABLE.listen(List.of("x"), null));
    }

    /** Counts session closes. */
    private static final class CountingVoiceInput implements VoiceInput {

        final AtomicInteger closes = new AtomicInteger();
        volatile boolean isAvailable;

        @Override
        public boolean available() {
            return isAvailable;
        }

        @Override
        public VoiceSession listen(List<String> phrases, Consumer<Heard> onHeard) {
            return closes::incrementAndGet;
        }
    }
}
