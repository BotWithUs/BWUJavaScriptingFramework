package com.botwithus.bot.cli.events;

import com.botwithus.bot.cli.events.HostEvent.ClientForgotten;
import com.botwithus.bot.cli.events.HostEvent.ClientIdentified;
import com.botwithus.bot.cli.events.HostEvent.ClientOpened;
import com.botwithus.bot.cli.events.HostEvent.ScriptStarted;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the real key table and host bus; only pipe liveness is faked. */
class ClientKeysTest {

    private static final Duration FLUSH = Duration.ofSeconds(10);
    private static final String PIPE = "BotWithUs_4242";
    private static final String OTHER_PIPE = "BotWithUs_5151";
    private static final String UUID = "0123456789abcdef0123456789abcdef";
    private static final Optional<String> NAME = Optional.of("Zezima");
    private static final Instant AT = Instant.parse("2026-09-26T12:00:00Z");
    private static final int SECOND = 2;

    private final HostEventBus bus = new HostEventBus();
    private final List<HostEvent> published = new CopyOnWriteArrayList<>();
    private final Set<String> livePipes = new HashSet<>();
    private final ClientKeys keys = new ClientKeys(bus, livePipes::contains);

    ClientKeysTest() {
        bus.subscribe(published::add);
    }

    @AfterEach
    void closeBus() {
        bus.close();
    }

    @Test
    void aPipeIsKeyedByItselfUntilIdentified_thenByItsAccount() {
        open(PIPE);
        assertEquals(ClientKey.pipe(PIPE), keys.refFor(PIPE).key());

        assertTrue(keys.identify(PIPE, UUID, NAME, AT));

        assertEquals(ClientKey.account(UUID), keys.refFor(PIPE).key());
        assertEquals(new ClientIdentified(new ClientRef(ClientKey.account(UUID), PIPE), NAME, AT),
                publishedEvents().getLast());
    }

    @Test
    void readingTheSameAccountAgain_publishesNothingMore() {
        open(PIPE);
        keys.identify(PIPE, UUID, NAME, AT);

        assertFalse(keys.identify(PIPE, UUID, Optional.of("Renamed"), AT));

        assertEquals(1, count(ClientIdentified.class));
    }

    @Test
    void aDevelopmentClient_isIdentifiedUnderItsPipeKey_once() {
        open(PIPE);

        assertTrue(keys.identify(PIPE, "dev_uuid", Optional.empty(), AT));
        assertFalse(keys.identify(PIPE, "", Optional.empty(), AT), "still the same pipe-keyed client");

        assertEquals(ClientKey.pipe(PIPE), keys.refFor(PIPE).key());
        assertEquals(1, count(ClientIdentified.class));
    }

    @Test
    void eventsPublishedAfterIdentifying_carryTheAccountKey() {
        open(PIPE);
        keys.publish(PIPE, client -> new ScriptStarted(client, "Before", AT));
        keys.identify(PIPE, UUID, NAME, AT);
        keys.publish(PIPE, client -> new ScriptStarted(client, "After", AT));

        List<HostEvent> events = publishedEvents();
        assertEquals(ClientKey.pipe(PIPE), keyOf(events.get(1)));
        assertEquals(ClientKey.account(UUID), keyOf(events.getLast()));
    }

    @Test
    void aSecondLiveClientOnTheSameAccount_getsTheNextInstance() {
        open(PIPE);
        open(OTHER_PIPE);
        keys.identify(PIPE, UUID, NAME, AT);

        keys.identify(OTHER_PIPE, UUID, NAME, AT);

        assertEquals(ClientKey.account(UUID), keys.refFor(PIPE).key());
        assertEquals(new ClientKey.Account(UUID, SECOND), keys.refFor(OTHER_PIPE).key());
    }

    @Test
    void aRestartedClient_takesItsAccountKeyFromTheDeadPipe() {
        open(PIPE);
        keys.identify(PIPE, UUID, NAME, AT);
        livePipes.remove(PIPE);
        open(OTHER_PIPE);

        keys.identify(OTHER_PIPE, UUID, NAME, AT);

        assertEquals(ClientKey.account(UUID), keys.refFor(OTHER_PIPE).key());
        assertEquals(ClientKey.pipe(PIPE), keys.refFor(PIPE).key(),
                "the dead pipe's late events must not land on the account");
    }

    @Test
    void forgetting_unbindsTheAccountsPipes_andSaysSo() {
        open(PIPE);
        keys.identify(PIPE, UUID, NAME, AT);
        livePipes.remove(PIPE);

        keys.forget(ClientKey.account(UUID), AT);

        assertTrue(keys.pipesOf(ClientKey.account(UUID)).isEmpty());
        assertEquals(new ClientForgotten(new ClientRef(ClientKey.account(UUID), PIPE), AT),
                publishedEvents().getLast());
    }

    @Test
    void anOpenedPipeIsReportedUnderItsPipeKey() {
        open(PIPE);

        assertEquals(new ClientOpened(new ClientRef(PIPE), AT), publishedEvents().getFirst());
    }

    private void open(String pipe) {
        livePipes.add(pipe);
        keys.opened(pipe, AT);
    }

    private List<HostEvent> publishedEvents() {
        assertTrue(bus.flush(FLUSH), "host events were not delivered");
        return List.copyOf(published);
    }

    private long count(Class<? extends HostEvent> type) {
        return publishedEvents().stream().filter(event -> event.getClass() == type).count();
    }

    private static ClientKey keyOf(HostEvent event) {
        return switch (event) {
            case HostEvent.ClientEvent client -> client.client().key();
            case HostEvent.ScriptLoadFailed _, HostEvent.ManagementAction _,
                 HostEvent.ManagementScriptCrashed _ -> throw new AssertionError("not about a client");
        };
    }
}
