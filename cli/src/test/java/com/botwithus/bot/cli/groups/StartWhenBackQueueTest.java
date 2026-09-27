package com.botwithus.bot.cli.groups;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The real queue over a real file in a temporary folder. */
class StartWhenBackQueueTest {

    private static final String UUID_A = "0123456789abcdef0123456789abcdef";
    private static final String UUID_B = "fedcba9876543210fedcba9876543210";
    private static final Instant T1 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-01T10:05:00Z");

    @TempDir
    Path dir;

    private StartWhenBackQueue queue() {
        return new StartWhenBackQueue(dir.resolve(StartWhenBackQueue.FILE_NAME));
    }

    /** The queue as the host's next run would load it. */
    private StartWhenBackQueue afterRestart() {
        StartWhenBackQueue queue = queue();
        queue.load();
        return queue;
    }

    @Test
    void queuedStarts_surviveARestart_inOrder() {
        StartWhenBackQueue queue = queue();
        queue.enqueue(UUID_A, "Woodcutter", T1);
        queue.enqueue(UUID_B, "Fisher", T2);

        assertEquals(List.of(new QueuedStart(UUID_A, "Woodcutter", T1), new QueuedStart(UUID_B, "Fisher", T2)),
                afterRestart().all());
    }

    @Test
    void theSameStartIsQueuedOnce_keepingTheFirstRequestTime() {
        StartWhenBackQueue queue = queue();
        assertTrue(queue.enqueue(UUID_A, "Woodcutter", T1));

        assertFalse(queue.enqueue(UUID_A, "woodcutter", T2));

        assertEquals(List.of(new QueuedStart(UUID_A, "Woodcutter", T1)), afterRestart().all());
    }

    @Test
    void startsAreListedByAccount() {
        StartWhenBackQueue queue = queue();
        queue.enqueue(UUID_A, "Woodcutter", T1);
        queue.enqueue(UUID_B, "Fisher", T1);
        queue.enqueue(UUID_A, "Fletcher", T2);

        assertEquals(List.of("Woodcutter", "Fletcher"),
                queue.forAccount(UUID_A).stream().map(QueuedStart::script).toList());
    }

    @Test
    void dequeuing_matchesTheScriptIgnoringCase_andIsSaved() {
        StartWhenBackQueue queue = queue();
        queue.enqueue(UUID_A, "Woodcutter", T1);
        queue.enqueue(UUID_A, "Fletcher", T1);

        assertTrue(queue.dequeue(UUID_A, "WOODCUTTER"));
        assertFalse(queue.dequeue(UUID_B, "Fletcher"), "another account's start is not touched");

        assertEquals(List.of(new QueuedStart(UUID_A, "Fletcher", T1)), afterRestart().all());
    }

    @Test
    void wholeAccountsScriptsAndTheQueueCanBeCleared() {
        StartWhenBackQueue queue = queue();
        queue.enqueue(UUID_A, "Woodcutter", T1);
        queue.enqueue(UUID_A, "Fletcher", T1);
        queue.enqueue(UUID_B, "Woodcutter", T1);
        queue.enqueue(UUID_B, "Fisher", T1);

        assertEquals(2, queue.dequeueScript("woodcutter"));
        assertEquals(1, queue.dequeueAll(UUID_A));
        assertEquals(1, queue.clear());
        assertTrue(afterRestart().all().isEmpty());
    }

    @Test
    void onlyARealAccountCanBeQueuedFor() {
        StartWhenBackQueue queue = queue();

        assertThrows(IllegalArgumentException.class, () -> queue.enqueue("dev_uuid", "Woodcutter", T1));
        assertThrows(IllegalArgumentException.class, () -> queue.enqueue("", "Woodcutter", T1));
        assertThrows(IllegalArgumentException.class, () -> queue.enqueue(UUID_A, " ", T1));
    }

    @Test
    void anUnreadableEntry_isSkipped_andTheRestLoad() throws IOException {
        Files.writeString(dir.resolve(StartWhenBackQueue.FILE_NAME), """
                {"version": 1, "queued": [
                  {"uuid": "%s", "script": "Woodcutter"},
                  {"uuid": "dev_uuid", "script": "Woodcutter", "requestedAt": "%s"},
                  {"uuid": "%s", "script": "Fisher", "requestedAt": "%s"}
                ]}""".formatted(UUID_A, T1, UUID_B, T2));

        assertEquals(List.of(new QueuedStart(UUID_B, "Fisher", T2)), afterRestart().all());
    }

    @Test
    void anUnreadableFile_isSetAside_andTheQueueStartsEmpty() throws IOException {
        Path file = dir.resolve(StartWhenBackQueue.FILE_NAME);
        Files.writeString(file, "[oops");

        assertTrue(afterRestart().all().isEmpty());
        assertTrue(Files.exists(dir.resolve(StartWhenBackQueue.FILE_NAME + ".corrupt")));
    }
}
