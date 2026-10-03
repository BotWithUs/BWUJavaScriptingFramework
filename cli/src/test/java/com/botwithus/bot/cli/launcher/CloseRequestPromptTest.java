package com.botwithus.bot.cli.launcher;

import com.botwithus.bot.api.script.LauncherException;
import com.botwithus.bot.core.launcher.CloseDecision;
import com.botwithus.bot.core.launcher.CloseRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The close request between the reader thread and the modal: only "close now" closes the host. */
class CloseRequestPromptTest {

    private static final CloseRequest FIRST = new CloseRequest(17, "data_update", 2);
    private static final CloseRequest SECOND = new CloseRequest(18, "data_update", 1);

    private final List<String> acks = new ArrayList<>();
    private final CloseRequestPrompt prompt = new CloseRequestPrompt(
            (id, decision) -> acks.add(id + ":" + decision.wire()), Runnable::run, Optional.empty());

    @Test
    void answer_isAcknowledgedWithTheUsersDecision() {
        prompt.offer(FIRST);
        assertEquals(Optional.of(FIRST), prompt.current());
        prompt.answer(FIRST, CloseDecision.LATER);
        assertAll(() -> assertEquals(List.of("17:later"), acks),
                () -> assertEquals(Optional.empty(), prompt.current()),
                () -> assertFalse(prompt.takeShutdownRequest(), "only closing closes the host"));
    }

    @Test
    void declined_doesNotCloseTheHost() {
        prompt.offer(FIRST);
        prompt.answer(FIRST, CloseDecision.DECLINED);
        assertEquals(List.of("17:declined"), acks);
        assertFalse(prompt.takeShutdownRequest());
    }

    @Test
    void closing_asksForShutdownOnce_afterTheAck() {
        prompt.offer(FIRST);
        prompt.answer(FIRST, CloseDecision.CLOSING);
        assertAll(() -> assertEquals(List.of("17:closing"), acks),
                () -> assertTrue(prompt.takeShutdownRequest()),
                () -> assertFalse(prompt.takeShutdownRequest(), "once"));
    }

    @Test
    void noRequest_meansNothingHappens() {
        assertAll(() -> assertEquals(Optional.empty(), prompt.current()),
                () -> assertFalse(prompt.takeShutdownRequest()),
                () -> assertEquals(List.of(), acks));
    }

    @Test
    void newerRequest_replacesAnUnansweredOne_andAStaleAnswerIsIgnored() {
        prompt.offer(FIRST);
        prompt.offer(SECOND);
        prompt.answer(FIRST, CloseDecision.CLOSING);
        assertAll(() -> assertEquals(Optional.of(SECOND), prompt.current()),
                () -> assertEquals(List.of(), acks),
                () -> assertFalse(prompt.takeShutdownRequest()));
    }

    @Test
    void failedAck_stillClosesWhenTheUserSaidClose() {
        CloseRequestPrompt failing = new CloseRequestPrompt((id, d) -> {
            throw new LauncherException(LauncherException.SERVICE_UNAVAILABLE, "down");
        }, Runnable::run, Optional.empty());
        failing.offer(FIRST);
        failing.answer(FIRST, CloseDecision.CLOSING);
        assertTrue(failing.takeShutdownRequest());
    }
}
