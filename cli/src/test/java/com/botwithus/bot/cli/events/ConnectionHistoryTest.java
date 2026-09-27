package com.botwithus.bot.cli.events;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.api.runtime.Phase;
import com.botwithus.bot.cli.events.HostEvent.ClientClosed;
import com.botwithus.bot.cli.events.HostEvent.ClientForgotten;
import com.botwithus.bot.cli.events.HostEvent.ClientIdentified;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionHistoryTest {

    private static final int SMALL = 3;
    private static final ClientKey ACCOUNT = ClientKey.account("0123456789abcdef0123456789abcdef");

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
        assertEquals(List.of(ClientKey.pipe("a"), ClientKey.pipe("b")), history.clients());
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
    void forgettingAClientDropsItsHistoryAndRecordsTheForgetHostWide() {
        ConnectionHistory history = new ConnectionHistory();
        HostEvent keptOpen = opened("kept");
        List.of(opened("gone"), keptOpen, closed("gone")).forEach(history);
        HostEvent forgotten = new ClientForgotten(new ClientRef("gone"), next());

        history.accept(forgotten);

        assertEquals(List.of(), history.forClient("gone"));
        assertEquals(List.of(ClientKey.pipe("kept")), history.clients(), "a forgotten client leaves no history behind");
        assertEquals(List.of(keptOpen), history.forClient("kept"));
        assertEquals(List.of(forgotten), history.hostWide(),
                "the forget itself stays visible in the host-wide timeline");
        assertEquals(List.of(keptOpen, forgotten), history.merged());
    }

    @Test
    void identifyingAClient_movesWhatItsPipeGatheredOntoItsAccount() {
        ConnectionHistory history = new ConnectionHistory();
        HostEvent open = opened("a");
        HostEvent start = started("a", "Woodcutter");
        HostEvent identified = identified("a", ACCOUNT);
        HostEvent later = new ScriptStarted(new ClientRef(ACCOUNT, "a"), "Fisher", next());

        List.of(open, start, identified, later).forEach(history);

        assertEquals(List.of(open, start, identified, later), history.forClient(ACCOUNT));
        assertEquals(List.of(), history.forClient(ClientKey.pipe("a")), "the pipe key is retired");
        assertEquals(List.of(ACCOUNT), history.clients());
        assertEquals(history.forClient(ACCOUNT), history.forClient("a"), "the pipe still finds its client");
    }

    @Test
    void aClientBackOnANewPipe_continuesItsTimelineInOrder() {
        ConnectionHistory history = new ConnectionHistory(SMALL + 2);
        HostEvent firstOpen = opened("old");
        HostEvent firstId = identified("old", ACCOUNT);
        HostEvent closedOld = new ClientClosed(new ClientRef(ACCOUNT, "old"), CloseCause.CONNECTION_LOST, next());
        HostEvent secondOpen = opened("new");
        HostEvent secondId = identified("new", ACCOUNT);

        List.of(firstOpen, firstId, closedOld, secondOpen, secondId).forEach(history);

        assertEquals(List.of(firstOpen, firstId, closedOld, secondOpen, secondId), history.forClient(ACCOUNT));
    }

    @Test
    void mergingTwoHistories_keepsOnlyTheNewestUpToCapacity() {
        ConnectionHistory history = new ConnectionHistory(SMALL);
        HostEvent oldOpen = opened("old");
        HostEvent oldId = identified("old", ACCOUNT);
        HostEvent newOpen = opened("new");
        HostEvent newStart = started("new", "Woodcutter");
        HostEvent newId = identified("new", ACCOUNT);

        List.of(oldOpen, oldId, newOpen, newStart, newId).forEach(history);

        assertEquals(List.of(newOpen, newStart, newId), history.forClient(ACCOUNT));
    }

    @Test
    void forgettingAnAccount_dropsTheHistoryGatheredOnEveryPipe() {
        ConnectionHistory history = new ConnectionHistory();
        List.of(opened("a"), identified("a", ACCOUNT)).forEach(history);

        history.accept(new ClientForgotten(new ClientRef(ACCOUNT, ClientRef.NO_PIPE), next()));

        assertTrue(history.clients().isEmpty());
        assertEquals(List.of(), history.forClient("a"));
    }

    @Test
    void capacityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectionHistory(0));
    }

    private HostEvent opened(String pipe) {
        return new ClientOpened(new ClientRef(pipe), next());
    }

    private HostEvent identified(String pipe, ClientKey key) {
        return new ClientIdentified(new ClientRef(key, pipe), Optional.of("Zezima"), next());
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
