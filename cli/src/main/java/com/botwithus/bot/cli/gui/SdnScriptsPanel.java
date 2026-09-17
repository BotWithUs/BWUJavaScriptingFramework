package com.botwithus.bot.cli.gui;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.sdn.SdnCatalogueEntry;
import com.botwithus.bot.core.sdn.SdnCatalogueResult;
import com.botwithus.bot.core.sdn.SdnCatalogueSource;
import com.botwithus.bot.core.sdn.SdnInstallResult;
import com.botwithus.bot.core.sdn.SdnInstaller;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Browses the scripts the signed-in account owns or subscribes to, and installs
 * the ones the user picks.
 *
 * <p>Installed scripts are handed straight to the same {@link ScriptRuntime} that
 * holds local scripts, so from the moment they land they are started, stopped and
 * configured from the Scripts panel like anything else. This panel is the shop
 * front only: it deliberately owns no run controls of its own.
 */
public class SdnScriptsPanel implements GuiPanel {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_AVAILABLE = 1;
    private static final int FILTER_OWNED = 2;

    private static final float SPINNER_PERIOD_SEC = 1f;
    private static final float CARD_ROWS = 2.4f;
    private static final float CARD_PAD = 8f;
    private static final float CARD_ROUNDING = 6f;
    private static final float ACCENT_BAR_W = 3f;
    private static final float SEARCH_CHARS = 14f;
    private static final float WRAP_CHARS = 30f;

    private final ExecutorService executor;
    private final SdnCatalogueSource catalogue;
    private final SdnInstaller installer;

    private final ImString searchQuery = new ImString(128);
    private final Set<String> selected = new LinkedHashSet<>();
    private final ImBoolean checkboxState = new ImBoolean(false);

    private volatile SdnCatalogueResult result;
    private volatile boolean loading;
    private volatile boolean installing;
    private volatile String statusLine = "";
    private int filter = FILTER_ALL;
    private float spinnerPhase;

    public SdnScriptsPanel(ExecutorService executor) {
        this(executor, new SdnCatalogueSource(), new SdnInstaller());
    }

    SdnScriptsPanel(ExecutorService executor, SdnCatalogueSource catalogue, SdnInstaller installer) {
        this.executor = executor;
        this.catalogue = catalogue;
        this.installer = installer;
    }

    @Override
    public String title() {
        return "Scripts Store";
    }

    @Override
    public void render(CliContext ctx) {
        spinnerPhase += ImGui.getIO().getDeltaTime();
        if (result == null && !loading) {
            refresh();
        }

        renderToolbar();
        ImGui.spacing();
        GuiHelpers.subtleSeparator();
        ImGui.spacing();

        SdnCatalogueResult current = result;
        if (current == null) {
            renderBusy(statusLine);
            return;
        }
        renderResult(ctx, current);
    }

    private void renderResult(CliContext ctx, SdnCatalogueResult current) {
        switch (current) {
            case SdnCatalogueResult.Delivered delivered -> renderCatalogue(ctx, delivered);
            case SdnCatalogueResult.CourierUnavailable ignored -> renderNotice(Icons.PLUG,
                    "Start the launcher to see your scripts",
                    "The BotWithUs launcher fetches your subscriptions for you. "
                            + "Sign in there, then refresh.");
            case SdnCatalogueResult.NotSignedIn ignored -> renderNotice(Icons.USERS,
                    "Sign in to see your scripts",
                    "The launcher is running but no account is signed in. "
                            + "Sign in there, then refresh.");
            case SdnCatalogueResult.SubscriptionRequired ignored -> renderNotice(Icons.CROWN,
                    "This account needs a BotWithUs subscription",
                    "Subscribing to individual scripts is not enough to browse the store. "
                            + "Add a BotWithUs subscription, then refresh.");
            case SdnCatalogueResult.Failed failed -> renderNotice(Icons.WARNING,
                    "We could not load your scripts", failed.reason());
        }
    }

    // Toolbar ---------------------------------------------------------------

    private void renderToolbar() {
        if (loading || installing) {
            ImGui.textColored(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f,
                    spinnerGlyph());
            ImGui.sameLine(0, 8);
            GuiHelpers.textSecondary(statusLine);
            return;
        }
        if (GuiHelpers.buttonPrimary(Icons.ROTATE + "  Refresh")) {
            refresh();
        }
        ImGui.sameLine(0, 8);
        ImGui.pushItemWidth(ImGui.getFontSize() * SEARCH_CHARS);
        ImGui.inputTextWithHint("##sdnSearch", Icons.SEARCH + "  Filter scripts...", searchQuery);
        ImGui.popItemWidth();

        ImGui.sameLine(0, 12);
        renderFilterPill("All", FILTER_ALL);
        ImGui.sameLine(0, 4);
        renderFilterPill("Available here", FILTER_AVAILABLE);
        ImGui.sameLine(0, 4);
        renderFilterPill("Yours", FILTER_OWNED);
    }

    private void renderFilterPill(String label, int value) {
        boolean active = (filter == value);
        if (active) {
            ImGui.pushStyleColor(ImGuiCol.Button,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.2f);
            ImGui.pushStyleColor(ImGuiCol.Text,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);
        } else {
            ImGui.pushStyleColor(ImGuiCol.Button, 0f, 0f, 0f, 0f);
            ImGui.pushStyleColor(ImGuiCol.Text,
                    ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 1f);
        }
        if (ImGui.smallButton(label + "##sdnFilter" + value)) {
            filter = value;
        }
        ImGui.popStyleColor(2);
    }

    // Catalogue -------------------------------------------------------------

    private void renderCatalogue(CliContext ctx, SdnCatalogueResult.Delivered delivered) {
        if (delivered.stale()) {
            ImGui.textColored(ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 0.9f,
                    Icons.CLOCK + "  Showing the last catalogue we saw. The launcher is not answering.");
            ImGui.spacing();
        }
        List<SdnCatalogueEntry> visible = visibleEntries(delivered.entries());
        if (visible.isEmpty()) {
            renderEmpty(delivered.entries().isEmpty());
            return;
        }
        renderSummary(visible);
        ImGui.spacing();
        renderInstallBar(ctx);
        ImGui.spacing();

        ImGui.beginChild("##sdnCards", 0, ImGui.getContentRegionAvailY(), false);
        for (SdnCatalogueEntry entry : visible) {
            ImGui.pushID("sdn_" + entry.id());
            renderCard(entry);
            ImGui.popID();
        }
        ImGui.endChild();
    }

    private List<SdnCatalogueEntry> visibleEntries(List<SdnCatalogueEntry> all) {
        String search = searchQuery.get().trim().toLowerCase(Locale.ROOT);
        List<SdnCatalogueEntry> out = new ArrayList<>();
        for (SdnCatalogueEntry e : all) {
            if (filter == FILTER_AVAILABLE && !e.runsOnThisHost()) {
                continue;
            }
            if (filter == FILTER_OWNED && !e.isOwned()) {
                continue;
            }
            if (search.isEmpty() || matches(e, search)) {
                out.add(e);
            }
        }
        return out;
    }

    private static boolean matches(SdnCatalogueEntry e, String search) {
        return e.name().toLowerCase(Locale.ROOT).contains(search)
                || e.author().toLowerCase(Locale.ROOT).contains(search)
                || e.summary().toLowerCase(Locale.ROOT).contains(search);
    }

    private static void renderSummary(List<SdnCatalogueEntry> visible) {
        long installable = visible.stream().filter(SdnCatalogueEntry::runsOnThisHost).count();
        GuiHelpers.textSecondary(visible.size() + " script" + (visible.size() == 1 ? "" : "s"));
        ImGui.sameLine(0, 8);
        ImGui.textColored(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 0.7f,
                installable + " available here");
        long older = visible.size() - installable;
        if (older > 0) {
            ImGui.sameLine(0, 8);
            GuiHelpers.textMuted(older + " built for an older agent");
        }
    }

    private void renderInstallBar(CliContext ctx) {
        int count = selected.size();
        if (count == 0) {
            GuiHelpers.textMuted("Tick the scripts you want, then install them.");
            return;
        }
        if (GuiHelpers.buttonPrimary(Icons.DOWNLOAD + "  Install " + count + " script"
                + (count == 1 ? "" : "s"))) {
            List<String> ids = List.copyOf(selected);
            executor.submit(() -> install(ctx, ids));
        }
        ImGui.sameLine(0, 8);
        if (GuiHelpers.buttonSecondary("Clear")) {
            selected.clear();
        }
    }

    // One card --------------------------------------------------------------

    private void renderCard(SdnCatalogueEntry entry) {
        boolean installable = entry.runsOnThisHost();
        float availW = ImGui.getContentRegionAvailX();
        float cardH = ImGui.getTextLineHeightWithSpacing() * CARD_ROWS;
        float startX = ImGui.getCursorScreenPosX();
        float startY = ImGui.getCursorScreenPosY();

        drawCardBackground(startX, startY, availW, cardH, installable);

        ImGui.setCursorScreenPos(startX + CARD_PAD, startY + CARD_PAD);
        renderCardSelector(entry, installable);
        ImGui.sameLine(0, CARD_PAD);
        renderCardText(entry);

        ImGui.setCursorScreenPos(startX, startY + cardH + 4f);
        ImGui.dummy(0, 0);
    }

    private void renderCardSelector(SdnCatalogueEntry entry, boolean installable) {
        if (!installable) {
            GuiHelpers.textMuted(Icons.XMARK);
            return;
        }
        checkboxState.set(selected.contains(entry.id()));
        if (ImGui.checkbox("##pick", checkboxState)) {
            if (checkboxState.get()) {
                selected.add(entry.id());
            } else {
                selected.remove(entry.id());
            }
        }
    }

    private static void renderCardText(SdnCatalogueEntry entry) {
        ImGui.beginGroup();
        ImGui.textUnformatted(entry.name());
        if (entry.isOwned()) {
            ImGui.sameLine(0, 8);
            GuiHelpers.statusBadge("Yours",
                    ImGuiTheme.BLUE_ACCENT_R, ImGuiTheme.BLUE_ACCENT_G, ImGuiTheme.BLUE_ACCENT_B);
        }
        if (!entry.runsOnThisHost()) {
            ImGui.sameLine(0, 6);
            GuiHelpers.statusBadge("Older agent",
                    ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B);
        }
        GuiHelpers.textMuted(metaLine(entry));
        String summary = entry.summary();
        if (!summary.isBlank()) {
            ImGui.sameLine(0, 12);
            GuiHelpers.textSecondary(summary);
        }
        ImGui.endGroup();
    }

    private static String metaLine(SdnCatalogueEntry entry) {
        String author = entry.author().isBlank() ? "Unknown author" : entry.author();
        if (entry.version().isBlank()) {
            return author;
        }
        return author + "   " + Icons.TAG + " " + entry.version();
    }

    private static void drawCardBackground(float x, float y, float w, float h, boolean installable) {
        ImDrawList draw = ImGui.getWindowDrawList();
        int bg = ImGuiTheme.imCol32(ImGuiTheme.SURFACE_R, ImGuiTheme.SURFACE_G,
                ImGuiTheme.SURFACE_B, 1f);
        int border = ImGuiTheme.imCol32(ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G,
                ImGuiTheme.BORDER_B, 0.3f);
        draw.addRectFilled(x, y, x + w, y + h, bg, CARD_ROUNDING);
        draw.addRect(x, y, x + w, y + h, border, CARD_ROUNDING);
        if (installable) {
            int accent = ImGuiTheme.imCol32(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G,
                    ImGuiTheme.ACCENT_B, 0.7f);
            draw.addRectFilled(x, y + 2f, x + ACCENT_BAR_W, y + h - 2f, accent, 2f);
        }
    }

    // States other than a delivered catalogue -------------------------------

    private void renderBusy(String message) {
        ImGui.dummy(0, ImGui.getFontSize() * 2f);
        ImGui.textColored(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f,
                spinnerGlyph());
        ImGui.sameLine(0, 8);
        GuiHelpers.textSecondary(message);
    }

    private void renderNotice(String icon, String headline, String body) {
        ImGui.dummy(0, ImGui.getFontSize() * 2f);
        ImGui.textColored(ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 1f,
                icon);
        ImGui.spacing();
        ImGui.textUnformatted(headline);
        ImGui.spacing();
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + ImGui.getFontSize() * WRAP_CHARS);
        GuiHelpers.textSecondary(body);
        ImGui.popTextWrapPos();
        ImGui.spacing();
        if (GuiHelpers.buttonPrimary(Icons.ROTATE + "  Try again")) {
            refresh();
        }
    }

    private void renderEmpty(boolean catalogueEmpty) {
        if (catalogueEmpty) {
            renderNotice(Icons.BOOKMARK, "No scripts on your account yet",
                    "Scripts you buy or subscribe to on the BotWithUs site show up here, "
                            + "ready to install.");
            return;
        }
        ImGui.dummy(0, ImGui.getFontSize() * 2f);
        GuiHelpers.textSecondary("Nothing matches that filter.");
    }

    private String spinnerGlyph() {
        boolean lit = (spinnerPhase % SPINNER_PERIOD_SEC) < (SPINNER_PERIOD_SEC / 2f);
        return lit ? Icons.CIRCLE_DOT : Icons.CIRCLE;
    }

    // Work, off the render thread -------------------------------------------

    private void refresh() {
        loading = true;
        statusLine = "Asking the launcher for your scripts...";
        executor.submit(() -> {
            try {
                result = catalogue.fetch();
            } finally {
                loading = false;
            }
        });
    }

    private void install(CliContext ctx, List<String> ids) {
        installing = true;
        statusLine = "Fetching " + ids.size() + " script" + (ids.size() == 1 ? "" : "s") + "...";
        try {
            reportInstall(ctx, installer.install(ids));
        } finally {
            installing = false;
        }
    }

    private void reportInstall(CliContext ctx, SdnInstallResult outcome) {
        switch (outcome) {
            case SdnInstallResult.Installed installed -> {
                register(ctx, installed.scripts());
                selected.clear();
            }
            case SdnInstallResult.NothingSelected ignored ->
                    ctx.out().println("Pick at least one script to install.");
            case SdnInstallResult.DeliveryDisabled ignored -> ctx.out().println(
                    "This host was not started with script delivery active. "
                            + "Launch it from the BotWithUs launcher to install scripts.");
            case SdnInstallResult.CourierUnavailable ignored -> ctx.out().println(
                    "The launcher did not deliver the scripts. "
                            + "Check it is still running, then try again.");
            case SdnInstallResult.Failed failed ->
                    ctx.out().println("Could not install: " + failed.reason());
        }
    }

    /**
     * Hands the delivered scripts to every live connection's runtime, which is
     * what gives them the same controls as a locally installed script.
     */
    private static void register(CliContext ctx, List<BotScript> scripts) {
        int connections = 0;
        for (Connection conn : ctx.getConnections()) {
            if (!conn.isAlive()) {
                continue;
            }
            ScriptRuntime runtime = conn.getRuntime();
            for (BotScript script : scripts) {
                runtime.registerScript(script);
            }
            connections++;
        }
        if (connections == 0) {
            ctx.out().println("Installed " + scripts.size() + " script(s). "
                    + "Connect to a client and they will be ready in the Scripts tab.");
            return;
        }
        ctx.out().println("Installed " + scripts.size() + " script(s) on "
                + connections + " connection(s). Start them from the Scripts tab.");
    }
}
