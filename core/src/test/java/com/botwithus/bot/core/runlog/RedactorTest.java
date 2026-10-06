package com.botwithus.bot.core.runlog;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RedactorTest {

    /** Spec §3.1's known names. Accounts are listed email-first on purpose: numbering is first-seen. */
    private static final KnownNames SPEC_NAMES = new KnownNames(
            List.of("dave.smith@example.com", "Main Acc"), List.of("Zezima"));

    /** Every spec §3.1 vector, in spec order, through one instance (numbering depends on it). */
    @Test
    void specVectors_inOrder_onOneInstance() {
        Redactor r = Redactor.withNames(SPEC_NAMES);
        String[][] vectors = {
            {"Logged in as Zezima at Lumbridge", "Logged in as Player#1 at Lumbridge"},
            {"connection Main Acc ready", "connection Account#1 ready"},
            {"user dave.smith@example.com failed", "user Account#2 failed"},
            {"contact other.person@mail.co.uk", "contact <email>"},
            {"Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sgn_abc-123", "Bearer <token>"},
            {"session=a1b2c3d4e5f6g7h8; path=/", "session=<redacted>; path=/"},
            {"{\"password\": \"hunter2hunter2\"}", "{\"password\": \"<redacted>\"}"},
            {"password=hunter2", "password=<redacted>"},
            {"digest 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                "digest <redacted>"},
            {"connect 209.38.7.81:443", "connect <ip>:443"},
            {"revision 950-1 on 10.0.26200", "revision 950-1 on 10.0.26200"},
            {"at C:\\Users\\david\\.botwithus\\scripts\\x.jar", "at ~\\.botwithus\\scripts\\x.jar"},
            {"tile (3222, 3218, 0) hp=99", "tile (3222, 3218, 0) hp=99"},
        };
        for (String[] v : vectors) {
            assertEquals(v[1], r.redact(v[0]), "vector: " + v[0]);
        }
    }

    @Nested
    class KnownNameMatching {

        @Test
        void sameName_keepsItsNumber_acrossLinesAndCase() {
            Redactor r = Redactor.withNames(SPEC_NAMES);
            assertAll(
                    () -> assertEquals("Player#1 here", r.redact("Zezima here")),
                    () -> assertEquals("Player#1 again", r.redact("ZEZIMA again")),
                    () -> assertEquals("Account#1 / Player#1", r.redact("main acc / zezima")));
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "hello Main_Acc!|hello Account#1!",
            "hello Main-Acc!|hello Account#1!",
            "hello Main\u00A0Acc!|hello Account#1!"})
        void separatorVariants_ofAName_areTheSameName(String input, String expected) {
            assertEquals(expected, Redactor.withNames(SPEC_NAMES).redact(input));
        }

        @Test
        void aNameInsideALongerWord_isNotEaten() {
            Redactor r = Redactor.withNames(new KnownNames(List.of(), List.of("Bob")));
            assertAll(
                    () -> assertEquals("Bobby met Player#1.", r.redact("Bobby met Bob.")),
                    () -> assertEquals("xBob", r.redact("xBob")));
        }

        @Test
        void underscoreIsAWordCharacter_atAMatchsEdges() {
            Redactor r = Redactor.withNames(SPEC_NAMES);
            assertAll(
                    () -> assertEquals("x_Zezima", r.redact("x_Zezima")),
                    () -> assertEquals("Zezima_2", r.redact("Zezima_2")),
                    () -> assertEquals("(Player#1)", r.redact("(Zezima)")));
        }

        @Test
        void aOneCharacterName_isIgnored() {
            Redactor r = Redactor.withNames(new KnownNames(List.of("Z"), List.of("Al")));
            assertEquals("Z met Player#1", r.redact("Z met Al"));
        }

        @Test
        void theLongerOfTwoOverlappingNames_wins() {
            Redactor r = Redactor.withNames(new KnownNames(List.of("Main", "Main Acc"), List.of()));
            assertEquals("Account#1 and Account#2", r.redact("Main Acc and Main"));
        }

        @Test
        void aNameLearnedMidRun_isRedactedFromThenOn() {
            AtomicReference<KnownNames> names = new AtomicReference<>(KnownNames.NONE);
            Redactor r = new Redactor(names::get);
            assertEquals("Zezima logs in", r.redact("Zezima logs in"));
            names.set(SPEC_NAMES);
            assertEquals("Player#1 logs in", r.redact("Zezima logs in"));
        }

        @Test
        void regexCharactersInAName_areLiteral() {
            Redactor r = Redactor.withNames(new KnownNames(List.of("a.b (c)"), List.of()));
            assertAll(
                    () -> assertEquals("x Account#1 y", r.redact("x a.b (c) y")),
                    () -> assertEquals("x aXb (c) y", r.redact("x aXb (c) y")));
        }
    }

    @Nested
    class Secrets {

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "apiKey=0123456789abc|apiKey=<redacted>",
            "token: abcdefgh|token: <redacted>",
            "cookie=abc|cookie=abc",
            "secret=x|secret=<redacted>",
            "PASSWD:p4ss|PASSWD:<redacted>",
            "password=|password=",
            "authToken='abcdefghijk'|authToken='<redacted>'",
            "{\"token\":\"abcdefghij\"}|{\"token\":\"<redacted>\"}",
            "{\"password\":\"x\"}|{\"password\":\"<redacted>\"}"})
        void keywordValues(String input, String expected) {
            assertEquals(expected, Redactor.withNames(KnownNames.NONE).redact(input));
        }

        @Test
        void hexAndBase64_onlyFromTheirLengthThreshold() {
            Redactor r = Redactor.withNames(KnownNames.NONE);
            String hex31 = "a".repeat(31);
            String hex32 = "b".repeat(32);
            String b64x39 = "Zy9".repeat(13);
            String b64x40 = "Zy9/".repeat(10);
            assertAll(
                    () -> assertEquals("h " + hex31, r.redact("h " + hex31)),
                    () -> assertEquals("h <redacted>", r.redact("h " + hex32)),
                    () -> assertEquals("b " + b64x39, r.redact("b " + b64x39)),
                    () -> assertEquals("b <redacted>", r.redact("b " + b64x40 + "==")));
        }

        @Test
        void base64_needsUpperLowerAndDigit_hexDoesNot() {
            Redactor r = Redactor.withNames(KnownNames.NONE);
            String path = "/Users/someone/AppData/Local/Programs/Python/Scripts";
            String noDigit = "AbCdEfGhIj".repeat(4);
            String noUpper = "abc123xyz4".repeat(4);
            String hexUpper = "ABCDEF0123".repeat(4);
            assertAll(
                    () -> assertEquals("p " + path, r.redact("p " + path)),
                    () -> assertEquals("b " + noDigit, r.redact("b " + noDigit)),
                    () -> assertEquals("b " + noUpper, r.redact("b " + noUpper)),
                    () -> assertEquals("h <redacted>", r.redact("h " + hexUpper)));
        }

        @Test
        void redactKeepingHashes_skipsOnlyTheHashRule() {
            Redactor r = Redactor.withNames(SPEC_NAMES);
            String runId = "3f9c1a2be0d84c1e9a7f5d2b6c0e4a11";
            assertAll(
                    () -> assertEquals(runId, r.redactKeepingHashes(runId)),
                    () -> assertEquals("<redacted>", r.redact(runId)),
                    () -> assertEquals("Player#1", r.redactKeepingHashes("Zezima")));
        }
    }

    @Nested
    class NetworkAndPaths {

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "peer 2001:db8::8a2e:370:7334 up|peer <ip> up",
            "peer ::ffff:10.1.2.3 up|peer <ip> up",
            "at 07:12:23.529 ok|at 07:12:23.529 ok",
            "Foo.java:88|Foo.java:88",
            "Java 25.0.1|Java 25.0.1"})
        void addresses(String input, String expected) {
            assertEquals(expected, Redactor.withNames(KnownNames.NONE).redact(input));
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "at C:/Users/someone/.botwithus/x.log|at ~/.botwithus/x.log",
            "at d:\\users\\Some One\\x|at ~\\x",
            "at D:\\Users\\someone\\x|at ~\\x",
            // JSON-escaped form, spec §3 revision 3.
            "{\"p\":\"C:\\\\Users\\\\someone\\\\x\"}|{\"p\":\"~\\\\x\"}",
            "at /home/someone/.botwithus/x|at ~/.botwithus/x"})
        void homeDirectories(String input, String expected) {
            assertEquals(expected, Redactor.withNames(KnownNames.NONE).redact(input));
        }
    }

    /** Spec §3 revision 4: the process's own profile prefix, wherever it lives. */
    @Nested
    class OwnProfilePrefix {

        private final Redactor r = new Redactor(() -> KnownNames.NONE, "D:\\Profiles\\jdoe");

        @Test
        void specVector() {
            assertEquals("at ~\\x.jar", r.redact("at D:\\Profiles\\jdoe\\x.jar"));
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
            "at d:/profiles/JDOE/x.jar|at ~/x.jar",
            "{\"p\":\"D:\\\\Profiles\\\\jdoe\\\\x\"}|{\"p\":\"~\\\\x\"}",
            "cwd D:\\Profiles\\jdoe|cwd ~",
            // The spec's three boundary cases: a sibling, a sibling with a dot, and a
            // sentence that ends in the profile path.
            "at D:\\Profiles\\jdoe2\\x|at D:\\Profiles\\jdoe2\\x",
            "at D:\\Profiles\\jdoe.bak\\x|at D:\\Profiles\\jdoe.bak\\x",
            "home is D:\\Profiles\\jdoe.|home is ~.",
            "see 'D:\\Profiles\\jdoe' now|see '~' now",
            "at D:\\Profiles\\jdoe-old\\x|at D:\\Profiles\\jdoe-old\\x",
            "at E:\\Profiles\\jdoe\\x|at E:\\Profiles\\jdoe\\x"})
        void spellingsAndBoundaries(String input, String expected) {
            assertEquals(expected, r.redact(input));
        }

        @Test
        void aRootOrMissingProfile_isNotAPrefix() {
            assertAll(
                    () -> assertEquals("at C:\\x", new Redactor(() -> KnownNames.NONE, "C:\\").redact("at C:\\x")),
                    () -> assertEquals("at /x", new Redactor(() -> KnownNames.NONE, "/").redact("at /x")),
                    () -> assertEquals("at /x", new Redactor(() -> KnownNames.NONE, null).redact("at /x")));
        }

        @Test
        void aUnixProfile_withATrailingSlash() {
            Redactor unix = new Redactor(() -> KnownNames.NONE, "/srv/users/jdoe/");
            assertEquals("at ~/x.jar", unix.redact("at /srv/users/jdoe/x.jar"));
        }
    }

    @Test
    void nothingToRedact_isReturnedUnchanged() {
        Redactor r = Redactor.withNames(SPEC_NAMES);
        String line = "2026-10-06T07:12:23.529Z INFO  [script-Foo] com.example.Foo: chopping tree";
        assertEquals(line, r.redact(line));
        assertFalse(r.redact("").contains("#"));
    }
}
