package com.botwithus.bot.cli.gui.pages;

import com.botwithus.bot.cli.gui.nav.NavBadge;
import com.botwithus.bot.cli.gui.nav.Page;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.gui.usermode.UserModeRenderer;
import com.botwithus.bot.cli.gui.usermode.board.ClientBoard;

import java.util.Optional;

/**
 * The client grid. It is Normal mode's whole screen and the first page of
 * Advanced, drawn by the same renderer over the same board in both, so
 * switching modes never loses the everyday view.
 */
public final class ClientsPage implements Page {

    private final UserModeRenderer renderer;
    private final ClientBoard board;

    public ClientsPage(UserModeRenderer renderer, ClientBoard board) {
        this.renderer = renderer;
        this.board = board;
    }

    @Override
    public PageId id() {
        return PageId.CLIENTS;
    }

    @Override
    public void render() {
        renderer.render(board);
    }

    @Override
    public Optional<NavBadge> badge() {
        int n = board.clients().size();
        return n > 0 ? Optional.of(NavBadge.count(n)) : Optional.empty();
    }
}
