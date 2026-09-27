package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.events.ClientRef;
import com.botwithus.bot.cli.gui.notify.ToastFeed;
import com.botwithus.bot.cli.gui.usermode.board.ClientState;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;

import java.util.Optional;

/**
 * The toast feed's view of the fixture fleet: names come from the cards, and a
 * client is gone when its card shows it closed.
 */
final class FixtureToasts implements ToastFeed.Clients {

    private final FixtureBoard board;

    FixtureToasts(FixtureBoard board) {
        this.board = board;
    }

    /** The client whose card shows {@code account}, as a host event would name it. */
    ClientRef ref(String account) {
        ClientView card = card(account);
        return new ClientRef(card.id(), card.pipe().orElse(ClientRef.NO_PIPE));
    }

    @Override
    public Optional<String> nameOf(ClientKey key) {
        return board.clients().stream().filter(c -> c.id().equals(key)).flatMap(c -> c.account().stream())
                .findFirst();
    }

    @Override
    public boolean isGone(ClientRef client) {
        return board.clients().stream().filter(c -> c.id().equals(client.key()))
                .anyMatch(c -> isClosed(c.state()));
    }

    private ClientView card(String account) {
        return board.clients().stream().filter(c -> c.account().filter(account::equals).isPresent())
                .findFirst().orElseThrow(() -> new IllegalArgumentException("no card for " + account));
    }

    private static boolean isClosed(ClientState state) {
        return switch (state) {
            case ClientState.Closed _ -> true;
            case ClientState.Identifying _, ClientState.Connected _, ClientState.NotResponding _,
                 ClientState.Resuming _ -> false;
        };
    }
}
