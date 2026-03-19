package com.botwithus.bot.cli.gui;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptCategory;
import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.core.runtime.ScriptProfiler;
import com.botwithus.bot.core.runtime.ScriptRunner;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Scripts management panel — card-based layout with category icons,
 * filter pills, sort controls, and bulk actions.
 */
public class ScriptsPanel implements GuiPanel {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_RUNNING = 1;
    private static final int FILTER_STOPPED = 2;

    // Sort modes
    private static final int SORT_NAME_AZ = 0;
    private static final int SORT_NAME_ZA = 1;
    private static final int SORT_CATEGORY = 2;
    private static final int SORT_STATUS = 3;
    private static final int SORT_AUTHOR = 4;

    private final ExecutorService executor;
    private final ImBoolean autoStartOnReload = new ImBoolean(false);
    private final ImInt selectedConnection = new ImInt(0);
    private final ImString searchQuery = new ImString(128);
    private int statusFilter = FILTER_ALL;
    /** null means "All Categories" */
    private ScriptCategory categoryFilter = null;
    private int sortMode = SORT_NAME_AZ;
    private final ImInt sortIdx = new ImInt(0);

    public ScriptsPanel(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public String title() {
        return "Scripts";
    }

    @Override
    public void render(CliContext ctx) {
        // ── Toolbar Row 1: Reload, Auto-start, Watcher ──
        if (GuiHelpers.buttonPrimary(Icons.ROTATE + "  Reload")) {
            boolean startAfter = autoStartOnReload.get();
            executor.submit(() -> reloadScripts(ctx, startAfter));
        }
        ImGui.sameLine(0, 8);
        ImGui.checkbox("Auto-start", autoStartOnReload);

        ImGui.sameLine(0, 24);
        boolean watcherRunning = ctx.isWatcherRunning();
        if (watcherRunning) {
            if (GuiHelpers.buttonDanger(Icons.STOP + "  Watcher")) {
                ctx.stopScriptWatcher();
            }
            ImGui.sameLine(0, 8);
            GuiHelpers.statusBadge(Icons.EYE + " Watching",
                    ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B);
        } else {
            if (GuiHelpers.buttonSecondary(Icons.EYE + "  Watcher")) {
                ctx.startScriptWatcher();
            }
        }

        // Connection selector if multiple connections
        var connections = new ArrayList<>(ctx.getConnections());
        if (connections.isEmpty()) {
            ImGui.spacing();
            GuiHelpers.textMuted("No active connections. Connect first via the Connections tab.");
            return;
        }

        if (connections.size() > 1) {
            ImGui.sameLine(0, 24);
            GuiHelpers.textSecondary("Connection:");
            ImGui.sameLine();
            ImGui.pushItemWidth(160);
            String[] connNames = connections.stream().map(Connection::getName).toArray(String[]::new);
            if (selectedConnection.get() >= connNames.length) {
                selectedConnection.set(0);
            }
            ImGui.combo("##connSelector", selectedConnection, connNames);
            ImGui.popItemWidth();
        } else {
            selectedConnection.set(0);
        }

        Connection conn = connections.get(selectedConnection.get());
        ScriptRuntime runtime = conn.getRuntime();
        List<ScriptRunner> runners = new ArrayList<>(runtime.getRunners());

        if (runners.isEmpty()) {
            ImGui.spacing();
            ImGui.spacing();
            GuiHelpers.textMuted("No scripts loaded. Click 'Reload' to discover scripts.");
            return;
        }

        ImGui.spacing();

        // ── Toolbar Row 2: Search + Status Filters + Sort + Bulk Actions ──
        ImGui.pushItemWidth(200);
        ImGui.inputTextWithHint("##scriptSearch", Icons.SEARCH + "  Filter scripts...", searchQuery);
        ImGui.popItemWidth();

        ImGui.sameLine(0, 12);
        renderStatusFilter("All", FILTER_ALL);
        ImGui.sameLine(0, 4);
        renderStatusFilter(Icons.CIRCLE + " Running", FILTER_RUNNING);
        ImGui.sameLine(0, 4);
        renderStatusFilter(Icons.CIRCLE + " Stopped", FILTER_STOPPED);

        // Sort selector
        ImGui.sameLine(0, 16);
        GuiHelpers.textSecondary(Icons.SORT);
        ImGui.sameLine(0, 4);
        ImGui.pushItemWidth(120);
        String[] sortLabels = {
                Icons.SORT_AZ + " Name A-Z",
                Icons.SORT_ZA + " Name Z-A",
                Icons.LAYER + " Category",
                Icons.CIRCLE + " Status",
                Icons.USERS + " Author"
        };
        if (ImGui.combo("##sortMode", sortIdx, sortLabels)) {
            sortMode = sortIdx.get();
        }
        ImGui.popItemWidth();

        // Bulk actions
        ImGui.sameLine(0, 20);
        if (GuiHelpers.buttonPrimary(Icons.PLAY + "  Start All")) {
            for (ScriptRunner r : runners) {
                if (!r.isRunning()) r.start();
            }
        }
        ImGui.sameLine(0, 4);
        if (GuiHelpers.buttonDanger(Icons.STOP + "  Stop All")) {
            for (ScriptRunner r : runners) {
                if (r.isRunning()) r.stop();
            }
        }

        ImGui.spacing();

        // ── Toolbar Row 3: Category pill strip ──
        List<ScriptCategory> presentCategories = buildPresentCategories(runners);
        renderCategoryPills(presentCategories, runners);

        ImGui.spacing();
        GuiHelpers.subtleSeparator();
        ImGui.spacing();

        // ── Apply filters + sort ──
        String searchText = searchQuery.get().trim().toLowerCase(Locale.ROOT);
        List<ScriptRunner> filtered = filterRunners(runners, searchText);
        sortRunners(filtered);

        // ── Script Count Summary ──
        long runningCount = filtered.stream().filter(ScriptRunner::isRunning).count();
        long stoppedCount = filtered.size() - runningCount;
        GuiHelpers.textSecondary(filtered.size() + " script" + (filtered.size() != 1 ? "s" : ""));
        ImGui.sameLine(0, 8);
        ImGui.textColored(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 0.7f,
                runningCount + " running");
        ImGui.sameLine(0, 8);
        ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.7f,
                stoppedCount + " stopped");

        ImGui.spacing();

        // ── Script Cards (scrollable region) ──
        float availH = ImGui.getContentRegionAvailY();
        ImGui.beginChild("##scriptCards", 0, availH, false);

        // When sorting by category, render group headers
        ScriptCategory lastGroupCat = null;
        for (int i = 0; i < filtered.size(); i++) {
            ScriptRunner runner = filtered.get(i);
            ScriptCategory cat = getCategory(runner);

            // Category group header when sorting by category
            if (sortMode == SORT_CATEGORY && cat != lastGroupCat) {
                if (lastGroupCat != null) {
                    ImGui.spacing();
                }
                renderCategoryHeader(cat);
                lastGroupCat = cat;
            }

            ImGui.pushID("sc_" + i);
            renderScriptCard(ctx, runner);
            ImGui.popID();
        }

        ImGui.endChild();
    }

    // ── Category pill strip ──────────────────────────────────────────────────

    private void renderCategoryPills(List<ScriptCategory> categories, List<ScriptRunner> allRunners) {
        // "All" pill
        renderCategoryPill(null, countForCategory(allRunners, null));
        for (ScriptCategory cat : categories) {
            ImGui.sameLine(0, 4);
            renderCategoryPill(cat, countForCategory(allRunners, cat));
        }
    }

    private void renderCategoryPill(ScriptCategory category, int count) {
        boolean isAll = (category == null);
        boolean active = isAll ? (categoryFilter == null) : (category == categoryFilter);
        CategoryStyle.Style style = isAll ? null : CategoryStyle.of(category);

        String icon = isAll ? Icons.LAYER : style.icon();
        String label = isAll ? "All" : category.getDisplayName();
        String text = icon + " " + label + " " + count;
        String id = "##catPill_" + (isAll ? "all" : category.name());

        float pillR = isAll ? ImGuiTheme.TEXT_SEC_R : style.r();
        float pillG = isAll ? ImGuiTheme.TEXT_SEC_G : style.g();
        float pillB = isAll ? ImGuiTheme.TEXT_SEC_B : style.b();

        if (active) {
            ImGui.pushStyleColor(ImGuiCol.Button, pillR, pillG, pillB, 0.22f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, pillR, pillG, pillB, 0.32f);
            ImGui.pushStyleColor(ImGuiCol.Text, pillR, pillG, pillB, 1f);
        } else {
            ImGui.pushStyleColor(ImGuiCol.Button, 0f, 0f, 0f, 0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, pillR, pillG, pillB, 0.10f);
            ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f);
        }

        if (ImGui.smallButton(text + id)) {
            categoryFilter = isAll ? null : category;
        }
        ImGui.popStyleColor(3);
    }

    private int countForCategory(List<ScriptRunner> runners, ScriptCategory category) {
        if (category == null) return runners.size();
        int n = 0;
        for (ScriptRunner r : runners) {
            if (getCategory(r) == category) n++;
        }
        return n;
    }

    // ── Category group header ────────────────────────────────────────────────

    private void renderCategoryHeader(ScriptCategory category) {
        CategoryStyle.Style style = CategoryStyle.of(category);
        ImDrawList draw = ImGui.getWindowDrawList();

        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float lineH = ImGui.getTextLineHeight();

        // Accent bar
        int barCol = CategoryStyle.color(category, 0.6f);
        draw.addRectFilled(x, y + 2f, x + 3f, y + lineH - 2f, barCol, 2f);

        // Icon + category name
        int iconCol = CategoryStyle.color(category, 0.9f);
        int textCol = CategoryStyle.color(category, 0.7f);
        draw.addText(x + 10f, y, iconCol, style.icon());
        ImVec2 iconSize = new ImVec2();
        ImGui.calcTextSize(iconSize, style.icon());
        draw.addText(x + 10f + iconSize.x + 6f, y, textCol, category.getDisplayName());

        ImGui.dummy(0, lineH + 4f);
    }

    // ── Script card ──────────────────────────────────────────────────────────

    private void renderScriptCard(CliContext ctx, ScriptRunner runner) {
        ScriptManifest manifest = runner.getManifest();
        ScriptProfiler profiler = runner.getProfiler();
        boolean running = runner.isRunning();
        ScriptCategory category = getCategory(runner);
        CategoryStyle.Style catStyle = CategoryStyle.of(category);

        ImDrawList draw = ImGui.getWindowDrawList();
        float availW = ImGui.getContentRegionAvailX();
        float cardH = ImGui.getTextLineHeightWithSpacing() * 2.2f;
        float padX = 10f;
        float padY = 6f;

        float startX = ImGui.getCursorScreenPosX();
        float startY = ImGui.getCursorScreenPosY();

        // Card background
        int bgCol = ImGuiTheme.imCol32(ImGuiTheme.SURFACE_R, ImGuiTheme.SURFACE_G, ImGuiTheme.SURFACE_B, 1f);
        int borderCol = ImGuiTheme.imCol32(ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.3f);
        draw.addRectFilled(startX, startY, startX + availW, startY + cardH, bgCol, 6f);
        draw.addRect(startX, startY, startX + availW, startY + cardH, borderCol, 6f);

        // Left accent bar — category color when running, dim when stopped
        if (running) {
            int accentCol = CategoryStyle.color(category, 0.8f);
            draw.addRectFilled(startX, startY + 2f, startX + 3f, startY + cardH - 2f, accentCol, 2f);
        }

        // Hover highlight
        if (ImGui.isMouseHoveringRect(startX, startY, startX + availW, startY + cardH)) {
            int hoverCol = CategoryStyle.color(category, 0.05f);
            draw.addRectFilled(startX, startY, startX + availW, startY + cardH, hoverCol, 6f);
        }

        // ── Row 1: Category icon + Status dot + Name + Perf badge ──
        float row1Y = startY + padY;
        float contentX = startX + padX;

        // Category icon (color-coded)
        int catIconCol = CategoryStyle.color(category, 0.7f);
        draw.addText(contentX, row1Y, catIconCol, catStyle.icon());
        ImVec2 iconSize = new ImVec2();
        ImGui.calcTextSize(iconSize, catStyle.icon());
        float afterIcon = contentX + iconSize.x + 8f;

        // Status dot
        float dotX = afterIcon + 4f;
        float dotY = row1Y + ImGui.getTextLineHeight() / 2f;
        if (running) {
            int glowCol = ImGuiTheme.imCol32(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 0.2f);
            int dotCol = ImGuiTheme.imCol32(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 1f);
            draw.addCircleFilled(dotX, dotY, 5f, glowCol);
            draw.addCircleFilled(dotX, dotY, 3f, dotCol);
        } else {
            int dotCol = ImGuiTheme.imCol32(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.6f);
            draw.addCircleFilled(dotX, dotY, 3f, dotCol);
        }

        // Script name
        float nameX = dotX + 12f;
        int nameCol = ImGuiTheme.imCol32(ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 1f);
        draw.addText(nameX, row1Y, nameCol, runner.getScriptName());

        // Performance badge next to name
        ImVec2 nameSize = new ImVec2();
        ImGui.calcTextSize(nameSize, runner.getScriptName());
        float badgeX = nameX + nameSize.x + 12f;
        if (profiler.getLoopCount() > 0) {
            String perfText = String.format("%.1fms", profiler.avgLoopMs());
            ImVec2 perfSize = new ImVec2();
            ImGui.calcTextSize(perfSize, perfText);

            float bpX = 3f, bpY = 1f;
            int perfBg = ImGuiTheme.imCol32(ImGuiTheme.BLUE_ACCENT_R, ImGuiTheme.BLUE_ACCENT_G, ImGuiTheme.BLUE_ACCENT_B, 0.12f);
            int perfBorder = ImGuiTheme.imCol32(ImGuiTheme.BLUE_ACCENT_R, ImGuiTheme.BLUE_ACCENT_G, ImGuiTheme.BLUE_ACCENT_B, 0.25f);
            int perfTextCol = ImGuiTheme.imCol32(ImGuiTheme.BLUE_ACCENT_R, ImGuiTheme.BLUE_ACCENT_G, ImGuiTheme.BLUE_ACCENT_B, 0.85f);
            draw.addRectFilled(badgeX, row1Y, badgeX + perfSize.x + bpX * 2, row1Y + perfSize.y + bpY * 2, perfBg, 3f);
            draw.addRect(badgeX, row1Y, badgeX + perfSize.x + bpX * 2, row1Y + perfSize.y + bpY * 2, perfBorder, 3f);
            draw.addText(badgeX + bpX, row1Y + bpY, perfTextCol, perfText);
        }

        // ── Row 2: Author + Version + Category badge ──
        float row2Y = row1Y + ImGui.getTextLineHeightWithSpacing();
        String author = manifest != null && !manifest.author().isEmpty() ? manifest.author() : "Unknown";
        String version = manifest != null ? manifest.version() : "?";
        String meta = author + "  " + Icons.TAG + "  " + version;
        int metaCol = ImGuiTheme.imCol32(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.8f);
        draw.addText(nameX, row2Y, metaCol, meta);

        // Category badge (always shown, color-coded)
        ImVec2 metaSize = new ImVec2();
        ImGui.calcTextSize(metaSize, meta);
        float catBadgeX = nameX + metaSize.x + 12f;
        String catText = catStyle.icon() + " " + category.getDisplayName();
        ImVec2 catSize = new ImVec2();
        ImGui.calcTextSize(catSize, catText);
        float cpX = 4f, cpY = 1f;
        int catBg = CategoryStyle.color(category, 0.10f);
        int catBorder = CategoryStyle.color(category, 0.25f);
        int catTextCol = CategoryStyle.color(category, 0.85f);
        draw.addRectFilled(catBadgeX, row2Y, catBadgeX + catSize.x + cpX * 2, row2Y + catSize.y + cpY * 2, catBg, 3f);
        draw.addRect(catBadgeX, row2Y, catBadgeX + catSize.x + cpX * 2, row2Y + catSize.y + cpY * 2, catBorder, 3f);
        draw.addText(catBadgeX + cpX, row2Y + cpY, catTextCol, catText);

        // ── Action buttons (right-aligned) ──
        float btnAreaWidth = 140f;
        float btnX = startX + availW - btnAreaWidth - padX;
        float btnY = startY + (cardH - ImGui.getFrameHeight()) / 2f;

        ImGui.setCursorScreenPos(btnX, btnY);
        if (running) {
            if (GuiHelpers.smallButtonDanger(Icons.STOP + "##stop")) {
                runner.stop();
            }
            ImGui.sameLine(0, 4);
            if (ImGui.smallButton(Icons.REDO + "##restart")) {
                executor.submit(() -> {
                    runner.stop();
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    runner.start();
                });
            }
        } else {
            if (ImGui.smallButton(Icons.PLAY + "##start")) {
                runner.start();
            }
        }

        // Config button
        var configFields = runner.getConfigFields();
        boolean hasConfig = (configFields != null && !configFields.isEmpty()) || runner.getScript().getUI() != null;
        if (hasConfig) {
            ImGui.sameLine(0, 4);
            if (ImGui.smallButton(Icons.SLIDERS + "##cfg")) {
                ctx.openConfigPanel(runner);
            }
        }

        // Advance cursor past the card + spacing
        ImGui.setCursorScreenPos(startX, startY + cardH + 4f);
        ImGui.dummy(0, 0);
    }

    // ── Filtering ────────────────────────────────────────────────────────────

    private List<ScriptRunner> filterRunners(List<ScriptRunner> runners, String search) {
        List<ScriptRunner> result = new ArrayList<>();
        for (ScriptRunner r : runners) {
            // Status filter
            if (statusFilter == FILTER_RUNNING && !r.isRunning()) continue;
            if (statusFilter == FILTER_STOPPED && r.isRunning()) continue;

            // Category filter
            if (categoryFilter != null) {
                ScriptCategory cat = getCategory(r);
                if (cat != categoryFilter) continue;
            }

            // Search filter (matches name, author, category)
            if (!search.isEmpty()) {
                String name = r.getScriptName().toLowerCase(Locale.ROOT);
                ScriptManifest m = r.getManifest();
                String author = m != null ? m.author().toLowerCase(Locale.ROOT) : "";
                String catName = getCategory(r).getDisplayName().toLowerCase(Locale.ROOT);
                if (!name.contains(search) && !author.contains(search) && !catName.contains(search)) continue;
            }
            result.add(r);
        }
        return result;
    }

    // ── Sorting ──────────────────────────────────────────────────────────────

    private void sortRunners(List<ScriptRunner> runners) {
        Comparator<ScriptRunner> cmp = switch (sortMode) {
            case SORT_NAME_AZ -> Comparator.comparing(ScriptRunner::getScriptName, String.CASE_INSENSITIVE_ORDER);
            case SORT_NAME_ZA -> Comparator.comparing(ScriptRunner::getScriptName, String.CASE_INSENSITIVE_ORDER).reversed();
            case SORT_CATEGORY -> Comparator.comparing((ScriptRunner r) -> getCategory(r).ordinal())
                    .thenComparing(ScriptRunner::getScriptName, String.CASE_INSENSITIVE_ORDER);
            case SORT_STATUS -> Comparator.comparing((ScriptRunner r) -> !r.isRunning()) // running first
                    .thenComparing(ScriptRunner::getScriptName, String.CASE_INSENSITIVE_ORDER);
            case SORT_AUTHOR -> Comparator.comparing((ScriptRunner r) -> {
                        ScriptManifest m = r.getManifest();
                        return m != null && !m.author().isEmpty() ? m.author().toLowerCase(Locale.ROOT) : "zzz";
                    }).thenComparing(ScriptRunner::getScriptName, String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(ScriptRunner::getScriptName, String.CASE_INSENSITIVE_ORDER);
        };
        runners.sort(cmp);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static ScriptCategory getCategory(ScriptRunner runner) {
        ScriptManifest m = runner.getManifest();
        return m != null ? m.category() : ScriptCategory.UNCATEGORIZED;
    }

    private List<ScriptCategory> buildPresentCategories(List<ScriptRunner> runners) {
        Map<ScriptCategory, Boolean> seen = new LinkedHashMap<>();
        for (ScriptRunner r : runners) {
            seen.put(getCategory(r), Boolean.TRUE);
        }
        return new ArrayList<>(seen.keySet());
    }

    private void renderStatusFilter(String label, int filterValue) {
        boolean active = (statusFilter == filterValue);
        if (active) {
            ImGui.pushStyleColor(ImGuiCol.Button, ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.2f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.3f);
            ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);
        } else {
            ImGui.pushStyleColor(ImGuiCol.Button, 0f, 0f, 0f, 0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, ImGuiTheme.ELEVATED_R, ImGuiTheme.ELEVATED_G, ImGuiTheme.ELEVATED_B, 1f);
            ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 1f);
        }
        if (ImGui.smallButton(label + "##filter" + filterValue)) {
            statusFilter = filterValue;
        }
        ImGui.popStyleColor(3);
    }

    private void reloadScripts(CliContext ctx, boolean autoStart) {
        List<BotScript> scripts = ctx.loadScripts();
        List<BotScript> blueprints = ctx.loadBlueprints();

        for (Connection conn : ctx.getConnections()) {
            if (!conn.isAlive()) continue;
            ScriptRuntime runtime = conn.getRuntime();
            runtime.stopAll();
            for (BotScript script : scripts) {
                runtime.registerScript(script);
            }
            for (BotScript bp : blueprints) {
                runtime.registerScript(bp);
            }
            ctx.out().println("Reloaded " + (scripts.size() + blueprints.size()) + " script(s) on " + conn.getName());

            if (autoStart) {
                for (ScriptRunner runner : runtime.getRunners()) {
                    runner.start();
                }
                ctx.out().println("Auto-started all scripts on " + conn.getName());
            }
        }
    }
}
