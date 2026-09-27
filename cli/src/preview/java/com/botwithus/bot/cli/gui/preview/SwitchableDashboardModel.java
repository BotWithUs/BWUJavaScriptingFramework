package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.command.CommandRegistry;
import com.botwithus.bot.cli.gui.AnsiOutputBuffer;
import com.botwithus.bot.cli.gui.pages.dashboard.CommandConsole;
import com.botwithus.bot.cli.gui.pages.dashboard.ConsoleView;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardActions;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardModel;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardView;
import com.botwithus.bot.cli.gui.pages.dashboard.EventRow;
import com.botwithus.bot.cli.gui.pages.dashboard.LiveDashboardModel;
import com.botwithus.bot.cli.gui.pages.dashboard.LogLevel;
import com.botwithus.bot.cli.gui.pages.dashboard.LogsView;
import com.botwithus.bot.cli.gui.pages.dashboard.Scope;
import com.botwithus.bot.cli.gui.usermode.board.ClientActions;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DEV ONLY. The Dashboard's model in the preview: the fixture by default, or
 * the real {@link LiveDashboardModel} over a host with nothing connected, so
 * the preview also draws the live model's own output through the real page.
 */
final class SwitchableDashboardModel implements DashboardModel {

    private final FixtureDashboardModel fixture;
    private DashboardModel current;

    SwitchableDashboardModel(FixtureDashboardModel fixture) {
        this.fixture = fixture;
        this.current = fixture;
    }

    FixtureDashboardModel fixture() {
        return fixture;
    }

    /**
     * Switches to the live model over {@code ctx}, sharing {@code clientActions}
     * as the app shares the Clients board's. The console's command thread
     * is never started: the preview submits nothing, and an executor starts its
     * thread on the first task.
     */
    void useLive(CliContext ctx, ClientActions clientActions) {
        ExecutorService unused = Executors.newSingleThreadExecutor();
        CommandConsole console = new CommandConsole(new AnsiOutputBuffer(), new CommandRegistry(), unused, ctx,
                () -> { });
        current = new LiveDashboardModel(ctx, console, "scripts/", runner -> { }, clientActions,
                Clock.systemDefaultZone());
    }

    @Override
    public DashboardView view(Scope scope) {
        return current.view(scope);
    }

    @Override
    public LogsView logs(Scope scope, LogLevel level) {
        return current.logs(scope, level);
    }

    @Override
    public List<EventRow> events(Scope scope) {
        return current.events(scope);
    }

    @Override
    public ConsoleView console() {
        return current.console();
    }

    @Override
    public DashboardActions actions() {
        return current.actions();
    }
}
