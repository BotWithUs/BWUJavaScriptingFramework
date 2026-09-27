package com.botwithus.bot.cli.alerts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailySummaryJobTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final ManualClock clock = new ManualClock(Instant.parse("2026-09-26T12:00:00Z"));
    private final ManualScheduler scheduler = new ManualScheduler(clock);
    private final AtomicReference<LocalTime> summaryAt = new AtomicReference<>(LocalTime.of(22, 0));
    private final AtomicInteger emitted = new AtomicInteger();
    private final DailySummaryJob job =
            new DailySummaryJob(scheduler, clock, BERLIN, summaryAt::get, emitted::incrementAndGet);

    @ParameterizedTest
    @CsvSource({
            "2026-09-26T12:00:00Z, 22:00, 2026-09-26T20:00:00Z",
            "2026-09-26T20:00:00Z, 22:00, 2026-09-27T20:00:00Z",
            "2026-09-26T20:30:00Z, 22:00, 2026-09-27T20:00:00Z",
            "2026-09-26T21:59:00Z, 00:00, 2026-09-26T22:00:00Z",
            // Spring forward: 02:30 does not exist on 29 March in Berlin; it runs at 03:30 CEST.
            "2026-03-28T12:00:00Z, 02:30, 2026-03-29T01:30:00Z"})
    void nextRun_isTheNextLocalOccurrenceStrictlyAfterNow(String now, String at, String expected) {
        assertEquals(Instant.parse(expected),
                DailySummaryJob.nextRun(Instant.parse(now), BERLIN, LocalTime.parse(at)));
    }

    @Test
    void start_schedulesTheNextSummary() {
        job.start();

        assertEquals(List.of(Instant.parse("2026-09-26T20:00:00Z")), scheduler.pending());
    }

    @Test
    void theSummary_isEmittedOnceADay() {
        job.start();

        scheduler.advance(Duration.ofHours(8).minusMinutes(1));
        assertEquals(0, emitted.get(), "not before 22:00");
        scheduler.advance(Duration.ofMinutes(1));
        assertEquals(1, emitted.get());
        assertEquals(List.of(Instant.parse("2026-09-27T20:00:00Z")), scheduler.pending());

        scheduler.advance(Duration.ofDays(1));
        assertEquals(2, emitted.get());
    }

    @Test
    void reschedule_movesThePendingSummaryToTheNewTime() {
        job.start();
        summaryAt.set(LocalTime.of(18, 30));

        job.reschedule();

        assertEquals(List.of(Instant.parse("2026-09-26T16:30:00Z")), scheduler.pending());
    }

    @Test
    void close_dropsThePendingSummary() {
        job.start();

        job.close();
        scheduler.advance(Duration.ofDays(2));

        assertEquals(0, emitted.get());
        assertEquals(List.of(), scheduler.pending());
    }

    @Test
    void aFailingSummary_stillSchedulesTomorrows() {
        DailySummaryJob failing = new DailySummaryJob(scheduler, clock, BERLIN, summaryAt::get, () -> {
            throw new IllegalStateException("boom");
        });
        failing.start();

        scheduler.advance(Duration.ofHours(8));

        assertEquals(List.of(Instant.parse("2026-09-27T20:00:00Z")), scheduler.pending());
    }
}
