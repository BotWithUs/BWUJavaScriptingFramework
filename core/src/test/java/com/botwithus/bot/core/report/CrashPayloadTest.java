package com.botwithus.bot.core.report;

import com.botwithus.bot.core.runlog.CrashPhase;
import com.botwithus.bot.core.runlog.CrashSummary;
import com.botwithus.bot.core.runlog.KnownNames;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashPayloadTest {

    /** Three UTF-8 bytes each, so a byte cut lands mid-character two times in three. */
    private static final String EURO = "€";
    /** Four UTF-8 bytes and two Java chars. */
    private static final String EMOJI = "😀";

    private static CrashSummary crash(String stack, List<String> crumbs) {
        return new CrashSummary("3f9c1a2be0d84c1e9a7f5d2b6c0e4a11", Optional.empty(), CrashPhase.ON_LOOP, 12,
                Instant.EPOCH, "java.lang.NullPointerException: npc", "com.example.Foo.loop(Foo.java:88)",
                stack, crumbs);
    }

    @Test
    void of_shortCrash_passesThroughWithWirePhase() {
        CrashPayload p = CrashPayload.of(crash("  at a\n  at b", List.of("one", "two")));
        assertAll(
                () -> assertEquals("on_loop", p.phase()),
                () -> assertEquals("java.lang.NullPointerException: npc", p.exception()),
                () -> assertEquals("com.example.Foo.loop(Foo.java:88)", p.topFrame()),
                () -> assertEquals("  at a\n  at b", p.stack()),
                () -> assertEquals(List.of("one", "two"), p.breadcrumbs()));
    }

    @Test
    void of_longStack_isCutTo16KiBOnACharacterBoundary() {
        for (int shift = 0; shift < 3; shift++) {
            String stack = "a".repeat(shift) + EURO.repeat(CrashPayload.MAX_STACK_BYTES);
            String cut = CrashPayload.of(crash(stack, List.of())).stack();
            byte[] bytes = cut.getBytes(StandardCharsets.UTF_8);
            int s = shift;
            assertAll("shift " + shift,
                    () -> assertTrue(bytes.length <= CrashPayload.MAX_STACK_BYTES, "bytes " + bytes.length),
                    () -> assertTrue(bytes.length > CrashPayload.MAX_STACK_BYTES - EURO.length() * 3),
                    () -> assertFalse(cut.contains("�"), "a character was split"),
                    () -> assertTrue(stack.startsWith(cut), "the head is kept"),
                    () -> assertEquals(s, cut.indexOf(EURO)));
        }
    }

    @Test
    void cutUtf8_exactlyAtLimit_isUnchanged() {
        String text = "b".repeat(CrashPayload.MAX_STACK_BYTES);
        assertSame(text, CrashPayload.cutUtf8(text, CrashPayload.MAX_STACK_BYTES));
    }

    @Test
    void cutUtf8_fourByteCharacter_isDroppedWhole() {
        String text = "ab" + EMOJI;
        assertAll(
                () -> assertEquals("ab", CrashPayload.cutUtf8(text, 5)),
                () -> assertEquals("ab" + EMOJI, CrashPayload.cutUtf8(text, 6)));
    }

    @Test
    void of_manyBreadcrumbs_keepsTheNewest50InOrder() {
        List<String> crumbs = IntStream.range(0, 200).mapToObj(i -> "crumb " + i).toList();
        List<String> kept = CrashPayload.of(crash("", crumbs)).breadcrumbs();
        assertAll(
                () -> assertEquals(CrashPayload.MAX_BREADCRUMBS, kept.size()),
                () -> assertEquals("crumb 150", kept.getFirst()),
                () -> assertEquals("crumb 199", kept.getLast()));
    }

    @Test
    void of_longBreadcrumb_isCutTo200CharactersWithoutSplittingAPair() {
        String plain = "x".repeat(CrashPayload.MAX_BREADCRUMB_CHARS + 50);
        String paired = "y".repeat(CrashPayload.MAX_BREADCRUMB_CHARS - 1) + EMOJI + "zzz";
        List<String> kept = CrashPayload.of(crash("", List.of(plain, paired))).breadcrumbs();
        assertAll(
                () -> assertEquals("x".repeat(CrashPayload.MAX_BREADCRUMB_CHARS), kept.get(0)),
                () -> assertEquals("y".repeat(CrashPayload.MAX_BREADCRUMB_CHARS - 1) + EMOJI, kept.get(1)),
                () -> assertEquals(CrashPayload.MAX_BREADCRUMB_CHARS,
                        kept.get(1).codePointCount(0, kept.get(1).length())));
    }

    @Test
    void cutChars_atAndOneOverTheLimit() {
        int max = CrashPayload.MAX_BREADCRUMB_CHARS;
        String exact = "z".repeat(max);
        assertAll(
                () -> assertSame(exact, CrashPayload.cutChars(exact, max)),
                () -> assertEquals(exact, CrashPayload.cutChars(exact + "!", max)));
    }

    @Test
    void request_withoutCrash_writesExplicitNull_andOmitsUnknowns() {
        ReportRequest r = new ReportRequest("Agility", "agility", OptionalLong.empty(), Optional.empty(), "2.0",
                Optional.empty(), Optional.empty(), OptionalLong.empty(), KnownNames.NONE, Optional.empty(), "  ");
        JsonObject o = JsonParser.parseString(r.toJson()).getAsJsonObject();
        assertAll(
                () -> assertTrue(o.has("crash"), "crash must be present"),
                () -> assertTrue(o.get("crash").isJsonNull()),
                () -> assertFalse(o.has("script_id")),
                () -> assertFalse(o.has("run_id")),
                () -> assertFalse(o.has("game_pid")),
                () -> assertFalse(o.has("user_note")),
                () -> assertEquals("java", o.get("host").getAsString()),
                () -> assertEquals(0, o.getAsJsonObject("known_names").getAsJsonArray("accounts").size()));
    }

    @Test
    void request_withCrash_writesTheTrimmedObject() {
        CrashPayload p = CrashPayload.of(crash("  at a", List.of("rpc x")));
        ReportRequest r = new ReportRequest("Agility", "agility", OptionalLong.of(7), Optional.of("1"), "2.0",
                Optional.of("run"), Optional.empty(), OptionalLong.of(5), KnownNames.NONE, Optional.of(p),
                "n".repeat(ReportRequest.MAX_NOTE_CHARS + 10));
        JsonObject o = JsonParser.parseString(r.toJson()).getAsJsonObject();
        JsonObject c = o.getAsJsonObject("crash");
        assertAll(
                () -> assertEquals("on_loop", c.get("phase").getAsString()),
                () -> assertEquals("com.example.Foo.loop(Foo.java:88)", c.get("top_frame").getAsString()),
                () -> assertEquals("rpc x", c.getAsJsonArray("breadcrumbs").get(0).getAsString()),
                () -> assertEquals(ReportRequest.MAX_NOTE_CHARS, o.get("user_note").getAsString().length()));
    }
}
