package com.botwithus.bot.core.pipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipeClientPrefixMatchTest {

    @Test
    void matchesExactProducerName() {
        assertTrue(PipeClient.nameMatchesPrefix("BotWithUs_12345", "BotWithUs_"));
    }

    @Test
    void matchIsCaseInsensitive() {
        assertTrue(PipeClient.nameMatchesPrefix("botwithus_12345", "BotWithUs_"));
        assertTrue(PipeClient.nameMatchesPrefix("BOTWITHUS_12345", "botwithus_"));
    }

    @Test
    void rejectsPrefixAppearingMidName() {
        // The old contains() match accepted these; startsWith() must not, so a
        // foreign or attacker-named pipe can't be picked up as a producer pipe.
        assertFalse(PipeClient.nameMatchesPrefix("x_BotWithUs_1", "BotWithUs_"));
        assertFalse(PipeClient.nameMatchesPrefix("evilBotWithUs_1", "BotWithUs_"));
        assertFalse(PipeClient.nameMatchesPrefix("not-a-match", "BotWithUs_"));
    }
}
