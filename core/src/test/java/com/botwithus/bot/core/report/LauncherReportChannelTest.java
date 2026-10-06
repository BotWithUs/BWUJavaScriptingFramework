package com.botwithus.bot.core.report;

import com.botwithus.bot.core.runlog.KnownNames;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LauncherReportChannelTest {

    private static final Duration CLAIM = Duration.ofMillis(400);
    private static final Duration REPLY = Duration.ofMillis(800);
    private static final Duration POLL = Duration.ofMillis(10);
    private static final String ID = "4242-3f9c1a2b-00ff";
    private static final String OK_REPLY = """
            {"status":"ok","code":"BWU-7K3Q9P","ticket_url":"https://example.test/t/1",
             "user_message":"Sent. Your report code is BWU-7K3Q9P."}""";

    @TempDir
    Path dir;

    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private final AtomicBoolean isStopping = new AtomicBoolean();

    @AfterEach
    void stopFakes() throws InterruptedException {
        isStopping.set(true);
        for (Thread t : threads) {
            t.join();
        }
    }

    private LauncherReportChannel channel() {
        return new LauncherReportChannel(dir, new LauncherReportChannel.Timing(CLAIM, REPLY, POLL));
    }

    private static ReportRequest request() {
        return new ReportRequest("Agility", "agility", OptionalLong.of(123), Optional.of("1.4.2"), "2.0",
                Optional.of("3f9c1a2be0d84c1e9a7f5d2b6c0e4a11"), Optional.empty(), OptionalLong.of(9001),
                new KnownNames(List.of("Main Acc"), List.of("Zezima")), Optional.empty(), "it stopped");
    }

    // ── A fake launcher ─────────────────────────────────────────────────────

    /** What the fake launcher does with a request it finds. */
    private enum Mode { ANSWER, CLAIM_ONLY, IGNORE }

    /**
     * Polls for requests and acts like the launcher: claims (delete the request,
     * write the working file), then writes the reply atomically. {@code answer}
     * turns the request JSON into the reply JSON.
     */
    private void fakeLauncher(Mode mode, Function<String, String> answer) {
        Thread t = Thread.ofVirtual().start(() -> {
            while (!isStopping.get()) {
                forEachRequest(req -> handle(mode, req, answer));
                pause();
            }
        });
        threads.add(t);
    }

    private void handle(Mode mode, Path req, Function<String, String> answer) {
        if (mode == Mode.IGNORE) {
            return;
        }
        String id = req.getFileName().toString().replace(LauncherReportChannel.REQUEST, "");
        try {
            String body = Files.readString(req, StandardCharsets.UTF_8);
            Files.delete(req);
            Files.writeString(dir.resolve(id + LauncherReportChannel.WORKING), "");
            if (mode == Mode.CLAIM_ONLY) {
                return;
            }
            Path tmp = dir.resolve(id + ".rpt.tmp");
            Files.writeString(tmp, answer.apply(body), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(id + LauncherReportChannel.REPLY), StandardCopyOption.ATOMIC_MOVE);
            Files.delete(dir.resolve(id + LauncherReportChannel.WORKING));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void forEachRequest(Consumer<Path> action) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + LauncherReportChannel.REQUEST)) {
            files.forEach(action);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> filesLeft() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).toList();
        }
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    void send_launcherAnswersOk_returnsCodeAndMessageAndCleansUp() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        fakeLauncher(Mode.ANSWER, body -> {
            seen.set(body);
            return OK_REPLY;
        });

        ReportReply reply = channel().send(ID, request());

        JsonObject sent = JsonParser.parseString(seen.get()).getAsJsonObject();
        assertAll(
                () -> assertEquals(new ReportReply.Sent("BWU-7K3Q9P", Optional.of("https://example.test/t/1"),
                        "Sent. Your report code is BWU-7K3Q9P."), reply),
                () -> assertEquals(1, sent.get("format").getAsInt()),
                () -> assertEquals("Agility", sent.get("script_name").getAsString()),
                () -> assertEquals(123, sent.get("script_id").getAsLong()),
                () -> assertTrue(sent.get("crash").isJsonNull()),
                () -> assertEquals("it stopped", sent.get("user_note").getAsString()),
                () -> assertEquals(List.of(), filesLeft()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not_signed_in", "script_not_found", "rate_limited", "too_large", "bad_request",
            "launcher_outdated", "network", "server_error", "bad_request_file", "bundle_failed"})
    void send_launcherAnswersError_showsItsMessageUnchanged(String error) throws Exception {
        String message = "Words for " + error + " — with \"quotes\" and é.";
        String retry = error.equals("rate_limited") ? ",\"retry_after\":3600" : "";
        JsonObject o = new JsonObject();
        o.addProperty("status", "error");
        o.addProperty("error", error);
        o.addProperty("user_message", message);
        String json = o.toString().replace("}", retry + "}");
        fakeLauncher(Mode.ANSWER, body -> json);

        ReportReply reply = channel().send(ID, request());

        OptionalLong expectedRetry = retry.isEmpty() ? OptionalLong.empty() : OptionalLong.of(3600);
        assertEquals(new ReportReply.Failed(error, expectedRetry, message), reply);
    }

    @Test
    void send_nobodyClaims_takesRequestBackAndSaysLauncherNotRunning() throws Exception {
        fakeLauncher(Mode.IGNORE, body -> OK_REPLY);

        long start = System.nanoTime();
        ReportReply reply = channel().send(ID, request());
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertAll(
                () -> assertEquals(ReportReply.Failed.launcherNotRunning(), reply),
                () -> assertEquals("The BotWithUs launcher isn't running. Open it, then try again.",
                        reply.userMessage()),
                () -> assertTrue(took.compareTo(CLAIM) >= 0, "gave up after " + took),
                () -> assertEquals(List.of(), filesLeft()));
    }

    @Test
    void send_claimedButNeverAnswered_timesOutCleanly() throws Exception {
        fakeLauncher(Mode.CLAIM_ONLY, body -> OK_REPLY);

        long start = System.nanoTime();
        ReportReply reply = channel().send(ID, request());
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertAll(
                () -> assertEquals(ReportReply.Failed.launcherTimeout(), reply),
                () -> assertTrue(took.compareTo(REPLY) >= 0, "gave up after " + took),
                // The working file is the launcher's; the host leaves it alone.
                () -> assertEquals(List.of(ID + LauncherReportChannel.WORKING), filesLeft()));
    }

    @Test
    void send_noDirectory_saysLauncherNotRunningAtOnce() throws Exception {
        LauncherReportChannel missing = new LauncherReportChannel(dir.resolve("absent"),
                new LauncherReportChannel.Timing(Duration.ofMinutes(1), REPLY, POLL));

        long start = System.nanoTime();
        ReportReply reply = missing.send(ID, request());

        assertAll(
                () -> assertEquals(ReportReply.Failed.launcherNotRunning(), reply),
                () -> assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(CLAIM) < 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[]", "{\"status\":\"ok\",\"user_message\":\"m\"}",
            "{\"status\":\"maybe\",\"user_message\":\"m\"}", "{\"status\":\"error\"}"})
    void send_unreadableReply_isBadReply(String json) throws Exception {
        fakeLauncher(Mode.ANSWER, body -> json);

        assertEquals(ReportReply.Failed.badReply(), channel().send(ID, request()));
    }

    /**
     * A reader that polls as fast as it can must never see a request under its
     * final name that is not a whole JSON object. The request is made large so a
     * non-atomic write would be observable mid-way.
     */
    @Test
    void send_requestAppearsWhole() throws Exception {
        List<String> torn = new CopyOnWriteArrayList<>();
        AtomicBoolean sawOne = new AtomicBoolean();
        Thread watcher = Thread.ofPlatform().start(() -> {
            while (!isStopping.get()) {
                forEachRequest(req -> {
                    try {
                        String body = Files.readString(req, StandardCharsets.UTF_8);
                        JsonParser.parseString(body).getAsJsonObject().get("script_name").getAsString();
                        sawOne.set(true);
                    } catch (IOException e) {
                        // Deleted or renamed under us: not a torn read.
                    } catch (RuntimeException e) {
                        torn.add(e.toString());
                    }
                });
            }
        });
        threads.add(watcher);
        String bigNote = "x".repeat(ReportRequest.MAX_NOTE_CHARS);
        ReportRequest big = new ReportRequest("Agility", "agility", OptionalLong.empty(), Optional.empty(), "2.0",
                Optional.empty(), Optional.empty(), OptionalLong.empty(), KnownNames.NONE,
                Optional.of(new CrashPayload("on_loop", "E", "f", "s".repeat(CrashPayload.MAX_STACK_BYTES),
                        List.of())), bigNote);

        for (int i = 0; i < 5; i++) {
            channel().send("atomic-" + i, big);
        }

        assertAll(
                () -> assertTrue(sawOne.get(), "the watcher never saw a request, so it proved nothing"),
                () -> assertEquals(List.of(), torn));
    }

    @Test
    void send_rejectsAnIdOutsideTheContract() {
        try {
            channel().send("../escape", request());
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("../escape"));
        } catch (InterruptedException e) {
            fail(e);
        }
    }

    @Test
    void newRequestId_carriesPidAndRunPrefix_andFitsTheContract() {
        LauncherReportChannel c = channel();
        String a = c.newRequestId(4242, Optional.of("3f9c1a2be0d84c1e9a7f5d2b6c0e4a11"));
        String b = c.newRequestId(4242, Optional.empty());
        assertAll(
                () -> assertTrue(a.matches("4242-3f9c1a2b-[0-9a-f]{4}"), a),
                () -> assertTrue(b.matches("4242-norun-[0-9a-f]{4}"), b));
    }

    @Test
    void directory_envOverridesHome() {
        Path home = Path.of("home");
        assertAll(
                () -> assertEquals(home.resolve(".botwithus/reports"),
                        LauncherReportChannel.directory(Map.of(), home)),
                () -> assertEquals(home.resolve(".botwithus/reports"),
                        LauncherReportChannel.directory(Map.of(LauncherReportChannel.DIR_ENV, "  "), home)),
                () -> assertEquals(Path.of("elsewhere"),
                        LauncherReportChannel.directory(Map.of(LauncherReportChannel.DIR_ENV, "elsewhere"), home)));
    }

    @Test
    void send_fakeLauncherIgnoring_neverLeavesTmp() throws Exception {
        fakeLauncher(Mode.IGNORE, body -> OK_REPLY);
        channel().send(ID, request());
        assertFalse(filesLeft().stream().anyMatch(f -> f.endsWith(".tmp")));
    }
}
