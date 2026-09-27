package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.api.runtime.ReconnectState;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.events.HostEvent;
import com.botwithus.bot.cli.events.HostEvent.ClientClosed;
import com.botwithus.bot.cli.events.HostEvent.ClientIdentified;
import com.botwithus.bot.cli.events.HostEvent.ClientOpened;
import com.botwithus.bot.cli.events.HostEvent.ClientResumed;
import com.botwithus.bot.cli.events.HostEvent.CloseCause;
import com.botwithus.bot.cli.events.HostEvent.ConnectionLost;
import com.botwithus.bot.cli.events.HostEvent.ReconnectStateChanged;
import com.botwithus.bot.cli.events.HostEvent.ScriptCrashed;
import com.botwithus.bot.cli.events.HostEvent.ScriptStalled;
import com.botwithus.bot.cli.events.HostEvent.ScriptStarted;
import com.botwithus.bot.cli.events.HostEvent.ScriptStopped;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A client's history, as the detail pane's timeline tells it. */
class TimelineTest {

    private static final String PIPE = "BotWithUs_10344";
    private static final ClientRef CLIENT = new ClientRef(ClientKey.account("e4410b7a9d254c6ba1f873e02b5d9c46"), PIPE);
    private static final Instant T0 = Instant.parse("2026-09-26T12:40:31Z");
    private static final String SCRIPT = "Woodcutting";
    private static final int ATTEMPT = 3;
    private static final long NEXT_MS = 4_000L;

    @Test
    void newestFirst() {
        List<HostEvent> events = List.of(
                new ClientOpened(CLIENT, at(0)),
                new ClientIdentified(CLIENT, Optional.of("Hollowmere"), at(1)),
                new ScriptStarted(CLIENT, SCRIPT, at(2)));

        assertEquals(List.of(at(2), at(1), at(0)), Timeline.of(events).stream().map(TimelineEntry::at).toList());
    }

    @Test
    void eachEventReadsAsTheDesignWritesIt() {
        LastCrash crash = new LastCrash(Phase.ON_LOOP, 0L, at(0), new IllegalStateException("boom"));
        List<HostEvent> events = List.of(
                new ClientOpened(CLIENT, at(0)),
                new ClientIdentified(CLIENT, Optional.of("Hollowmere"), at(1)),
                new ClientResumed(CLIENT, Optional.of("BotWithUs_8841"), at(2)),
                new ScriptStarted(CLIENT, SCRIPT, at(3)),
                new ScriptStalled(CLIENT, SCRIPT, at(4)),
                new ScriptCrashed(CLIENT, SCRIPT, crash, at(5)),
                new ScriptStopped(CLIENT, SCRIPT, at(6)),
                new ConnectionLost(CLIENT, new IOException("Pipe closed"), at(7)),
                new ReconnectStateChanged(CLIENT, new ReconnectState.Reconnecting(0L, ATTEMPT, NEXT_MS), at(8)),
                new ReconnectStateChanged(CLIENT, new ReconnectState.Connected(0L), at(9)),
                new ClientClosed(CLIENT, CloseCause.DISCONNECTED, at(10)));

        List<TimelineEntry> shown = Timeline.of(events).reversed();

        assertAll(
                () -> assertEquals(entry(0, Tone.NEUTRAL, "Pipe opened", PIPE), shown.get(0)),
                () -> assertEquals(entry(1, Tone.OK, "Connected", "account read · Hollowmere"), shown.get(1)),
                () -> assertEquals(entry(2, Tone.OK, "Resumed", "back from BotWithUs_8841"), shown.get(2)),
                () -> assertEquals(entry(3, Tone.OK, "Started", SCRIPT), shown.get(3)),
                () -> assertEquals(entry(4, Tone.WARN, "Stalled", SCRIPT), shown.get(4)),
                () -> assertEquals(entry(5, Tone.ERROR, "Crashed", SCRIPT), shown.get(5)),
                () -> assertEquals(entry(6, Tone.NEUTRAL, "Stopped", SCRIPT), shown.get(6)),
                () -> assertEquals(entry(7, Tone.ERROR, "Connection lost", "Pipe closed"), shown.get(7)),
                () -> assertEquals(entry(8, Tone.WARN, "Reconnecting", "attempt 3, next in 4 s"), shown.get(8)),
                () -> assertEquals(entry(9, Tone.OK, "Reconnected", ""), shown.get(9)),
                () -> assertEquals(entry(10, Tone.NEUTRAL, "Disconnected", ""), shown.get(10)));
    }

    @Test
    void aRunOfRetriesShowsOnlyTheLatest() {
        List<HostEvent> events = List.of(
                new ConnectionLost(CLIENT, new IOException("Pipe closed"), at(0)),
                new ReconnectStateChanged(CLIENT, new ReconnectState.Reconnecting(0L, 1, NEXT_MS), at(1)),
                new ReconnectStateChanged(CLIENT, new ReconnectState.Reconnecting(0L, 2, NEXT_MS), at(2)),
                new ReconnectStateChanged(CLIENT, new ReconnectState.Reconnecting(0L, ATTEMPT, NEXT_MS), at(3)));

        List<TimelineEntry> shown = Timeline.of(events);

        assertEquals(List.of("attempt 3, next in 4 s", "Pipe closed"),
                shown.stream().map(TimelineEntry::detail).toList());
    }

    @Test
    void longHistoriesKeepTheNewestEntries() {
        List<HostEvent> events = new ArrayList<>();
        for (int i = 0; i < Timeline.MAX_ENTRIES * 2; i++) {
            events.add(new ScriptStarted(CLIENT, SCRIPT, at(i)));
        }

        List<TimelineEntry> shown = Timeline.of(events);

        assertAll(
                () -> assertEquals(Timeline.MAX_ENTRIES, shown.size()),
                () -> assertEquals(at(Timeline.MAX_ENTRIES * 2 - 1), shown.getFirst().at()));
    }

    @Test
    void droppedAt_isTheStartOfTheCurrentOutage() {
        List<HostEvent> events = List.of(
                new ConnectionLost(CLIENT, new IOException("first"), at(0)),
                new ReconnectStateChanged(CLIENT, new ReconnectState.Connected(0L), at(1)),
                new ConnectionLost(CLIENT, new IOException("second"), at(2)),
                new ReconnectStateChanged(CLIENT, new ReconnectState.Disconnected(0L, null), at(3)),
                new ReconnectStateChanged(CLIENT, new ReconnectState.Reconnecting(0L, 1, NEXT_MS), at(4)));

        assertEquals(Optional.of(at(2)), Timeline.droppedAt(events));
    }

    @Test
    void droppedAt_isEmptyOnceTheClientCameBack() {
        List<HostEvent> events = List.of(
                new ConnectionLost(CLIENT, new IOException("gone"), at(0)),
                new ReconnectStateChanged(CLIENT, new ReconnectState.Connected(0L), at(1)));

        assertEquals(Optional.empty(), Timeline.droppedAt(events));
    }

    private static Instant at(int seconds) {
        return T0.plusSeconds(seconds);
    }

    private static TimelineEntry entry(int seconds, Tone tone, String lead, String detail) {
        return new TimelineEntry(at(seconds), tone, lead, detail);
    }
}
