package com.botwithus.bot.cli.gui.preview;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.nav.Page;
import com.botwithus.bot.cli.gui.nav.PageRegistry;
import com.botwithus.bot.cli.gui.nav.SecondLine;
import com.botwithus.bot.cli.gui.pages.ClientsPage;
import com.botwithus.bot.cli.gui.pages.dashboard.DashboardPage;
import com.botwithus.bot.cli.gui.pages.groups.GroupsPage;
import com.botwithus.bot.cli.gui.pages.installed.InstalledPage;
import com.botwithus.bot.cli.gui.pages.management.ManagementPage;
import com.botwithus.bot.cli.gui.pages.settings.FixtureSettingsModel;
import com.botwithus.bot.cli.gui.pages.settings.SettingsPage;
import com.botwithus.bot.cli.gui.pages.store.StorePage;
import com.botwithus.bot.cli.gui.pages.connections.ConnectionsPage;
import com.botwithus.bot.cli.gui.usermode.UserModeRenderer;
import com.botwithus.bot.cli.gui.usermode.board.ClientBoard;
import com.botwithus.bot.cli.log.LogBuffer;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DEV ONLY. The Advanced page set the preview draws: the real Clients page over
 * the fixture board, the real Dashboard over {@link FixtureDashboardModel}, the
 * real Connections page over a {@link FixtureConnectionsModel}, the real Groups
 * page over a {@link FixtureGroupsModel}, the real Installed scripts
 * page over a {@link FixtureInstalledModel}, the real Management page over a
 * {@link FixtureManagementModel}, the real Script Store over a
 * {@link FixtureStoreModel} and the real Settings page over in-memory settings.
 */
final class FixturePages {

    /** What a scenario can reach after building the pages. */
    record Built(PageRegistry registry, DashboardPage dashboard, SwitchableDashboardModel dashboardModel,
                 CliContext host, StorePage store, ConnectionsPage connections, InstalledPage installed,
                 FixtureInstalledModel installedModel, SettingsPage settings, FixtureSettingsModel settingsModel,
                 GroupsPage groups, FixtureGroupsModel groupsModel, ManagementPage management,
                 FixtureManagementModel managementModel) {}

    private static final Path CWD = Path.of("").toAbsolutePath();
    private static final Path HOME = Path.of(System.getProperty("user.home"));

    private FixturePages() {}

    static Built build(Controls ui, UserModeRenderer clients, ClientBoard board, FixtureStoreModel storeModel,
                       FixtureConnectionsModel connectionsModel) {
        CliContext ctx = new CliContext(new LogBuffer(), null);
        SwitchableDashboardModel dashboardModel = new SwitchableDashboardModel(
                new FixtureDashboardModel(FixtureDashboardModel.Fleet.BUSY));
        DashboardPage dashboard = new DashboardPage(ui, dashboardModel, Clock.systemDefaultZone());
        // The Store's "Open" switches page through the registry built below.
        AtomicReference<PageRegistry> registry = new AtomicReference<>();
        StorePage store = new StorePage(ui, storeModel, id -> registry.get().select(id));
        // The preview never clicks, so the detail pane's links lead nowhere.
        ConnectionsPage connections = new ConnectionsPage(ui, connectionsModel, id -> { });
        FixtureInstalledModel installedModel = new FixtureInstalledModel();
        InstalledPage installed = new InstalledPage(ui, installedModel,
                SecondLine.FolderPath.of(CWD.resolve("scripts"), CWD, HOME), id -> { });
        FixtureSettingsModel settingsModel = new FixtureSettingsModel();
        SettingsPage settings = new SettingsPage(ui, settingsModel);
        FixtureGroupsModel groupsModel = new FixtureGroupsModel(board.catalog());
        GroupsPage groups = new GroupsPage(ui, groupsModel);
        FixtureManagementModel managementModel = new FixtureManagementModel();
        ManagementPage management = new ManagementPage(ui, managementModel,
                SecondLine.FolderPath.of(CWD.resolve("scripts").resolve("management"), CWD, HOME), id -> { });
        List<Page> pages = List.of(
                new ClientsPage(clients, board),
                dashboard,
                connections,
                groups,
                installed,
                management,
                store,
                settings);
        registry.set(new PageRegistry(pages));
        return new Built(registry.get(), dashboard, dashboardModel, ctx, store, connections, installed,
                installedModel, settings, settingsModel, groups, groupsModel, management, managementModel);
    }
}
