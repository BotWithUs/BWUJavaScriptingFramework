package com.botwithus.bot.core.launcher;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A label is cut at 64 code points, as the service would cut it (amendment A5). */
class HelloParamsTest {

    @Test
    void shortLabel_isKept() {
        assertEquals(Optional.of("BotWithUs Java host"),
                new HelloParams("1", 1, Optional.of("BotWithUs Java host")).label());
    }

    @Test
    void longLabel_isCutAtSixtyFourCodePoints_notChars() {
        String emoji = "😀";
        String label = emoji.repeat(70);
        String kept = new HelloParams("1", 1, Optional.of(label)).label().orElseThrow();
        assertEquals(64, kept.codePointCount(0, kept.length()));
        assertEquals(emoji.repeat(64), kept);
    }
}
