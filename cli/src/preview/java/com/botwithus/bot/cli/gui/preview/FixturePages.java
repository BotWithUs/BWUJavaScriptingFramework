package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.GroupsPanel;
import com.botwithus.bot.cli.gui.nav.NavBadge;
import com.botwithus.bot.cli.gui.nav.Page;
import com.botwithus.bot.cli.gui.nav.PageId;
import com.botwithus.bot.cli.gui.nav.PageRegistry;
import com.botwithus.bot.cli.gui.nav.SecondLine;
import com.botwithus.bot.cli.gui.pages.ClientsPage;
import com.botwithus.bot.cli.gui.pages.LegacyPanelPage;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardPage;
import com.botwithus.bot.cli.gui.usermode.UserModeRenderer;
import com.botwithus.bot.cli.gui.usermode.board.ClientBoard;
import com.botwithus.bot.cli.log.LogBuffer;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * DEV ONLY. The Advanced page set the preview draws: the real Clients page over
 * the fixture board, the real Dashboard over {@link FixtureDashboardModel}, the
 * interim Groups page over a host with nothing connected, and fixture pages for
 * the rest so every kind of sidebar entry shows.
 */
final class FixturePages {

    /** What a scenario can reach after building the pages. */
    record Built(PageRegistry registry, DashboardPage dashboard, SwitchableDashboardModel dashboardModel,
                 CliContext host) {}

    private static final int INSTALLED_PROBLEMS = 3;
    private static final Path CWD = Path.of("").toAbsolutePath();
    private static final Path HOME = Path.of(System.getProperty("user.home"));

    private FixturePages() {}

    static Built build(Controls ui, UserModeRenderer clients, ClientBoard board, SecondLine.AccountStatus store) {
        CliContext ctx = new CliContext(new LogBuffer(), null);
        SwitchableDashboardModel dashboardModel = new SwitchableDashboardModel(
                new FixtureDashboardModel(FixtureDashboardModel.Fleet.BUSY));
        DashboardPage dashboard = new DashboardPage(ui, dashboardModel, Clock.systemDefaultZone());
        List<Page> pages = List.of(
                new ClientsPage(clients, board),
                dashboard,
                fixture(PageId.CONNECTIONS, ui, Optional.empty(), Optional.empty()),
                LegacyPanelPage.of(PageId.GROUPS, ui, ctx, new GroupsPanel()),
                fixture(PageId.INSTALLED, ui, folder("scripts"), Optional.of(NavBadge.problems(INSTALLED_PROBLEMS))),
                fixture(PageId.MANAGEMENT, ui, folder("scripts/management"), Optional.empty()),
                fixture(PageId.STORE, ui, Optional.of(store), Optional.empty()),
                fixture(PageId.SETTINGS, ui, Optional.empty(), Optional.empty()));
        return new Built(new PageRegistry(pages), dashboard, dashboardModel, ctx);
    }

    private static FixturePage fixture(PageId id, Controls ui, Optional<SecondLine> line, Optional<NavBadge> badge) {
        return new FixturePage(id, ui, line, badge);
    }

    /** The same short form the host shows for its real scripts folder under the working directory. */
    private static Optional<SecondLine> folder(String relative) {
        return Optional.of(SecondLine.FolderPath.of(CWD.resolve(relative), CWD, HOME));
    }
}
