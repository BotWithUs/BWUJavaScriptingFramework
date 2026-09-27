package com.botwithus.bot.cli.events;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.cli.events.HostEvent.ClientClosed;
import com.botwithus.bot.cli.events.HostEvent.ClientOpened;
import com.botwithus.bot.cli.events.HostEvent.CloseCause;
import com.botwithus.bot.cli.events.HostEvent.ManagementScriptCrashed;
import com.botwithus.bot.cli.events.HostEvent.ScriptLoadFailed;
import com.botwithus.bot.cli.events.HostEvent.ScriptStarted;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionHistoryTest {

    private static final int SMALL = 3;

    private long clock;

    @Test
    void keepsEachClientsEventsInArrivalOrder() {
        ConnectionHistory history = new ConnectionHistory();
        HostEvent openA = opened("a");
        HostEvent openB = opened("b");
        HostEvent startA = started("a", "Woodcutter");
        HostEvent closeB = closed("b");

        List.of(openA, openB, startA, closeB).forEach(history);

        assertEquals(List.of(openA, startA), history.forClient("a"));
        assertEquals(List.of(openB, closeB), history.forClient("b"));
        assertEquals(List.of("a", "b"), history.clients());
        assertEquals(List.of(), history.forClient("never-seen"));
    }

    @Test
    void evictsTheOldestEventsBeyondCapacity() {
        ConnectionHistory history = new ConnectionHistory(SMALL);
        List<HostEvent> events = new ArrayList<>();
        for (int n = 0; n < SMALL + 2; n++) {
            events.add(started("a", "script-" + n));
        }

        events.forEach(history);

        assertEquals(events.subList(2, events.size()), history.forClient("a"));
    }

    @Test
    void aBusyClientDoesNotEvictAQuietOne() {
        ConnectionHistory history = new ConnectionHistory(SMALL);
        HostEvent quiet = opened("quiet");
        history.accept(quiet);
        for (int n = 0; n < SMALL * 4; n++) {
            history.accept(started("busy", "script-" + n));
        }

        assertEquals(List.of(quiet), history.forClient("quiet"));
        assertEquals(SMALL, history.forClient("busy").size());
    }

    @Test
    void hostWideEventsAreKeptApartFromClients() {
        ConnectionHistory history = new ConnectionHistory();
        HostEvent loadFailed = new ScriptLoadFailed(Path.of("broken.jar"),
                new IllegalStateException("bad module"), next());
        HostEvent mgmtCrash = new ManagementScriptCrashed("Orchestrator",
                new LastCrash(Phase.ON_LOOP, 0, next(), new IllegalStateException("boom")), next());

        history.accept(loadFailed);
        history.accept(mgmtCrash);

        assertEquals(List.of(loadFailed, mgmtCrash), history.hostWide());
        assertTrue(history.clients().isEmpty(), "a host-wide event must not invent a client");
    }

    @Test
    void theMergedViewInterleavesClientsAndHostInArrivalOrder() {
        ConnectionHistory history = new ConnectionHistory(SMALL);
        HostEvent openA = opened("a");
        HostEvent loadFailed = new ScriptLoadFailed(Path.of("broken.jar"),
                new IllegalStateException("bad module"), next());
        HostEvent openB = opened("b");
        HostEvent startA = started("a", "Woodcutter");

        List.of(openA, loadFailed, openB, startA).forEach(history);

        assertEquals(List.of(openA, loadFailed, openB, startA), history.merged());
    }

    @Test
    void theMergedViewOmitsWhatEachClientEvicted() {
        ConnectionHistory history = new ConnectionHistory(SMALL);
        HostEvent quiet = opened("quiet");
        history.accept(quiet);
        List<HostEvent> busy = new ArrayList<>();
        for (int n = 0; n < SMALL * 2; n++) {
            busy.add(started("busy", "script-" + n));
        }
        busy.forEach(history);

        List<HostEvent> expected = new ArrayList<>();
        expected.add(quiet);
        expected.addAll(busy.subList(busy.size() - SMALL, busy.size()));
        assertEquals(expected, history.merged());
    }

    @Test
    void aSnapshotDoesNotChangeUnderItsReader() {
        ConnectionHistory history = new ConnectionHistory();
        history.accept(opened("a"));
        List<HostEvent> before = history.forClient("a");
        List<HostEvent> mergedBefore = history.merged();

        history.accept(started("a", "Woodcutter"));

        assertEquals(1, before.size());
        assertEquals(1, mergedBefore.size());
        assertThrows(UnsupportedOperationException.class, () -> before.add(opened("x")));
    }

    @Test
    void capacityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectionHistory(0));
    }

    private HostEvent opened(String pipe) {
        return new ClientOpened(new ClientRef(pipe), next());
    }

    private HostEvent closed(String pipe) {
        return new ClientClosed(new ClientRef(pipe), CloseCause.DISCONNECTED, next());
    }

    private HostEvent started(String pipe, String script) {
        return new ScriptStarted(new ClientRef(pipe), script, next());
    }

    /** Distinct, increasing instants, so no two events are equal by accident. */
    private Instant next() {
        return Instant.ofEpochSecond(++clock);
    }
}
