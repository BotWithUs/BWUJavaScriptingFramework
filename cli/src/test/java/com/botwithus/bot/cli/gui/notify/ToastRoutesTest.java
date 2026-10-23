package com.botwithus.bot.cli.gui.notify;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.notify.Notification.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Each toast button reaches the right host action, with the right client. */
class ToastRoutesTest {

    private static final ClientKey OAK = ClientKey.pipe("BotWithUs_1");

    private final List<String> calls = new ArrayList<>();
    private final ToastRoutes routes = new ToastRoutes(
            key -> calls.add("retryNow " + key),
            key -> calls.add("viewLog " + key),
            () -> calls.add("viewAllLogs"));

    @Test
    void tryAgain_retriesTheClientWhereItStands() {
        routes.accept(toast(Kind.GAVE_UP, Optional.of(OAK)));

        assertEquals(List.of("retryNow " + OAK), calls);
    }

    @Test
    void viewLog_onACrashOrStall_opensLogsScopedToTheClient() {
        routes.accept(toast(Kind.SCRIPT_CRASHED, Optional.of(OAK)));
        routes.accept(toast(Kind.SCRIPT_STALLED, Optional.of(OAK)));

        assertEquals(List.of("viewLog " + OAK, "viewLog " + OAK), calls);
    }

    @Test
    void details_onALoadFailure_opensEveryClientsLogs() {
        routes.accept(toast(Kind.LOAD_FAILED, Optional.empty()));

        assertEquals(List.of("viewAllLogs"), calls);
    }

    @Test
    void aToastWithNoButton_doesNothing() {
        routes.accept(toast(Kind.CLIENT_CLOSED, Optional.of(OAK)));
        routes.accept(toast(Kind.RECONNECTED, Optional.of(OAK)));

        assertEquals(List.of(), calls);
    }

    private static Notification toast(Kind kind, Optional<ClientKey> client) {
        return new Notification(UUID.randomUUID(), kind, "title", "message", client, MutableClock.START,
                Optional.empty());
    }
}
