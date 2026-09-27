package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Segment;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.inspector.InspectorState;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ClientScript;
import com.botwithus.bot.cli.gui.inspector.InspectorSubject.ManagementScript;
import com.botwithus.bot.cli.gui.inspector.InspectorTab;
import com.botwithus.bot.cli.gui.usermode.ClientFilter.View;
import com.botwithus.bot.cli.gui.usermode.board.BoardStatus;
import com.botwithus.bot.cli.gui.usermode.board.ClientBoard;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;
import com.botwithus.bot.cli.gui.usermode.board.ScriptRow;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Clients page — Normal mode's only screen, and the first page of
 * Advanced. A page header with counts and the view filter, a responsive grid of
 * {@link ClientCard}s, one per account, and the empty state. A script row's
 * "Settings" opens the shared inspector, which the shell docks beside this page.
 */
public class UserModeRenderer {

    private static final int QUERY_CAPACITY = 64;
    private static final float PARAGRAPH_CH = 34f;
    private static final float PARAGRAPH_LINE = 1.45f;
    private static final float RADAR_EM = 4.8f;
    private static final float RADAR_INNER_EM = 0.8f;
    private static final float RADAR_PERIOD_S = 1.6f;
    private static final float RADAR_SWEEP = (float) (Math.PI / 2);
    private static final float RADAR_STROKE_PX = 2f;
    private static final String ZERO = "0";
    private static final int PAGE_COLOR_COUNT = 5;
    /** The round-2 grid column minimum, 320 px at a 15 px body. */
    private static final float CARD_MIN_EM = 21.33f;
    /** The filter box is 200 px where a card is 320 px at the base size. */
    private static final float SEARCH_WIDTH_OF_CARD = 200f / 320f;

    private final Controls ui;
    private final ClientCard card;
    private final ScriptPickerPopup picker;
    private final InspectorState inspector;
    private final Consumer<String> openManagement;

    private final ImString query = new ImString(QUERY_CAPACITY);
    private final Map<ClientKey, Double> firstSeen = new HashMap<>();
    private View view = View.ALL;
    private ClientKey selectedId;
    /** The pipe the inspector was last opened on from elsewhere; selects its card once seen. */
    private String inspectedPipe;
    private InspectorSubject lastInspected;

    /** @param inspector the one inspector both modes share */
    public UserModeRenderer(Controls ui, InspectorState inspector) {
        this(ui, inspector, script -> { });
    }

    /**
     * @param inspector      the one inspector both modes share
     * @param openManagement opens Management on a management script, for a row's robot link
     */
    public UserModeRenderer(Controls ui, InspectorState inspector, Consumer<String> openManagement) {
        this.ui = ui;
        this.card = new ClientCard(ui);
        this.picker = new ScriptPickerPopup(ui);
        this.inspector = inspector;
        this.openManagement = openManagement;
    }

    /** Opens the picker for {@code client}, as its card's "Start script" would. */
    public void openPicker(ClientBoard board, ClientKey client) {
        board.clients().stream().filter(c -> c.id().equals(client)).findFirst()
                .ifPresent(c -> picker.open(c, board.catalog()));
    }

    /**
     * Opens the inspector on {@code scriptName} on {@code client}, as the
     * script row's "Settings" would. Does nothing while the client has no pipe,
     * since its scripts are not running anywhere to inspect.
     */
    public void openInspector(ClientBoard board, ClientKey client, String scriptName, InspectorTab tab) {
        board.clients().stream().filter(c -> c.id().equals(client)).findFirst()
                .ifPresent(c -> openInspector(c, scriptName, tab));
    }

    private void openInspector(ClientView client, String scriptName, InspectorTab tab) {
        client.pipe().ifPresent(pipe -> {
            inspector.open(new ClientScript(pipe, scriptName), tab);
            selectedId = client.id();
        });
    }

    /** Selects a view segment, as clicking it would. Package-private: the dev preview's seam. */
    void showView(View next) {
        view = next;
    }

    public void render(ClientBoard board) {
        pushPageColors();
        followInspector();
        renderPage(board);
        picker.render(board::subscriptions).ifPresent(pick -> startPick(board, pick));
        ImGui.popStyleColor(PAGE_COLOR_COUNT);
        ImGui.popStyleVar();
    }

    /**
     * Notes the pipe the inspector was just opened on, wherever it was opened
     * from (a row here, or a "Settings" button on another page), so its card is
     * selected once the page has its clients.
     */
    private void followInspector() {
        InspectorSubject now = inspector.subject().orElse(null);
        if (now != null && !now.equals(lastInspected)) {
            switch (now) {
                case ClientScript opened -> inspectedPipe = opened.clientId();
                case ManagementScript ignored -> { }
            }
        }
        lastInspected = now;
    }

    private void selectInspected(List<ClientView> clients) {
        if (inspectedPipe == null) {
            return;
        }
        String pipe = inspectedPipe;
        clients.stream().filter(c -> c.pipe().filter(pipe::equals).isPresent()).findFirst()
                .ifPresent(c -> selectedId = c.id());
        inspectedPipe = null;
    }

    /**
     * Hands the pick to the board. Only a local script offers "Review settings":
     * a subscription may still be installing, so there are no settings to review yet.
     */
    private void startPick(ClientBoard board, ScriptPickerPopup.Pick pick) {
        switch (pick.row()) {
            case PickerRow.Local local -> {
                board.actions().startScript(pick.client(), local.entry());
                if (pick.reviewSettings()) {
                    openInspector(board, pick.client(), local.entry().info().name(), InspectorTab.SETTINGS);
                }
            }
            case PickerRow.Subscribed sub -> board.actions().startSubscription(pick.client(), sub.entry().id());
        }
    }

    /** Package-private: the dev preview's seam for highlighting a picker row. */
    void highlightPickerRow(int index) {
        picker.highlight(index);
    }

    /** Focus ring in the focus blue, and a quiet scrollbar that only shows its thumb. */
    private void pushPageColors() {
        Controls.pushColor(ImGuiCol.NavHighlight, ImGuiTheme.COL_FOCUS);
        Controls.pushColor(ImGuiCol.ScrollbarBg, 0);
        Controls.pushColor(ImGuiCol.ScrollbarGrab, ImGuiTheme.COL_BORDER);
        Controls.pushColor(ImGuiCol.ScrollbarGrabHovered, ImGuiTheme.COL_BORDER_HOVER);
        Controls.pushColor(ImGuiCol.ScrollbarGrabActive, ImGuiTheme.COL_BORDER_HOVER);
        ImGui.pushStyleVar(ImGuiStyleVar.ScrollbarSize, ui.m().u(2));
    }

    // ── Page ───────────────────────────────────────────────────────────────

    private void renderPage(ClientBoard board) {
        List<ClientView> clients = board.clients();
        selectInspected(clients);
        firstSeen.keySet().retainAll(clients.stream().map(ClientView::id).toList());
        float headerH = renderHeader(clients);
        ImGuiTheme.Metrics m = ui.m();
        ImGui.setCursorPos(0f, headerH);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(5), m.u(1));
        ImGui.beginChild("##clients-grid", 0f, 0f, ImGuiChildFlags.AlwaysUseWindowPadding, 0);
        ImGui.popStyleVar();
        if (clients.isEmpty()) {
            renderWaiting(board.status());
        } else {
            renderGrid(board, ClientFilter.apply(clients, view, query.get()));
        }
        ImGui.endChild();
    }

    /** Title, counts and the filter row. Returns the header's height. */
    private float renderHeader(List<ClientView> clients) {
        ImGuiTheme.Metrics m = ui.m();
        float rowH = m.controlHeight();
        float x0 = ImGui.getWindowPosX() + m.u(5);
        float y0 = ImGui.getWindowPosY() + m.u(4);
        float right = ImGui.getWindowPosX() + ImGui.getWindowWidth() - m.u(5);
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont title = ui.fonts().titleMedium();
        ui.textCentredY(draw, title, x0, y0, rowH, ImGuiTheme.COL_FG, "Clients");
        long running = clients.stream().filter(ClientView::isRunning).count();
        if (!clients.isEmpty()) {
            String count = clients.size() + (clients.size() == 1 ? " client" : " clients") + " · "
                    + running + " running";
            ui.textCentredY(draw, ui.fonts().monoCaption(), x0 + ui.width(title, "Clients") + m.u(3), y0, rowH,
                    ImGuiTheme.COL_FG2, count);
            renderFilterRow(clients, (int) running, right, y0, rowH);
        }
        return m.u(4) + rowH + m.u(3);
    }

    private void renderFilterRow(List<ClientView> clients, int running, float right, float y, float rowH) {
        ImGuiTheme.Metrics m = ui.m();
        long attention = clients.stream().filter(ClientView::needsAttention).count();
        List<Segment> segments = List.of(
                new Segment("All", String.valueOf(clients.size()), false),
                new Segment("Running", String.valueOf(running), false),
                new Segment("Needs attention", attention > 0 ? null : ZERO, attention > 0));
        float segW = ui.segmentedWidth(segments);
        float segH = m.controlSmallHeight();
        float segX = right - segW;
        ImGui.setCursorScreenPos(segX, y + (rowH - segH) * 0.5f);
        int clicked = ui.segmented("##view", segments, view.ordinal(), 0f, segH);
        if (clicked >= 0) {
            view = View.values()[clicked];
        }
        if (!ClientFilter.showsSearch(clients.size())) {
            // The box is gone, so nothing may still be filtering by it; a stale
            // query would otherwise come back the next time the box appears.
            query.set("");
        } else {
            float searchW = cardMinWidth() * SEARCH_WIDTH_OF_CARD;
            ImGui.setCursorScreenPos(segX - m.u(3) - searchW, y);
            ui.searchBox("##client-filter", query, "Filter by account or script", searchW, rowH, false);
        }
    }

    private float cardMinWidth() {
        return ui.fonts().body().getFontSize() * CARD_MIN_EM;
    }

    // ── Grid ───────────────────────────────────────────────────────────────

    /**
     * Lays the cards out left to right in rows. Cards are as tall as their
     * content; each row is as tall as its tallest card, and cards sit at its top.
     */
    private void renderGrid(ClientBoard board, List<ClientView> visible) {
        if (visible.isEmpty()) {
            renderNoMatch();
            return;
        }
        float availW = ImGui.getContentRegionAvailX();
        float gap = ui.m().u(3);
        int columns = Math.max(1, (int) ((availW + gap) / (cardMinWidth() + gap)));
        float cardW = (availW - gap * (columns - 1)) / columns;
        float originX = ImGui.getCursorScreenPosX();
        float originY = ImGui.getCursorScreenPosY();
        float rowY = originY;
        for (int start = 0; start < visible.size(); start += columns) {
            List<ClientView> row = visible.subList(start, Math.min(visible.size(), start + columns));
            float rowH = 0f;
            for (int i = 0; i < row.size(); i++) {
                ClientView v = row.get(i);
                rowH = Math.max(rowH, card.height(v, cardW));
                float x = originX + i * (cardW + gap);
                handleIntent(board, v, card.render(v, x, rowY, cardW, v.id().equals(selectedId), appear(v.id()),
                        board.actions()));
            }
            rowY += rowH + gap;
        }
        ImGui.setCursorScreenPos(originX, originY);
        ImGui.dummy(availW, rowY - gap - originY + ui.m().u(5));
    }

    private float appear(ClientKey id) {
        double now = ImGui.getTime();
        double since = now - firstSeen.computeIfAbsent(id, k -> now);
        return (float) Math.min(1.0, since / ImGuiTheme.DURATION_S);
    }

    private void handleIntent(ClientBoard board, ClientView v, ClientCard.Intent intent) {
        switch (intent) {
            case ClientCard.Intent.None ignored -> { }
            case ClientCard.Intent.Select ignored -> selectedId = v.id();
            case ClientCard.Intent.StartScript ignored -> {
                selectedId = v.id();
                picker.open(v, board.catalog());
            }
            case ClientCard.Intent.Configure configure -> configure(v, configure.scriptName());
            case ClientCard.Intent.OpenManagement open -> openManagement.accept(open.managementScript());
        }
    }

    /** Opens the inspector on the row's script, on the tab its settings suggest. */
    private void configure(ClientView v, String scriptName) {
        Optional<ScriptInfo> script = v.script(scriptName).map(ScriptRow::script);
        script.ifPresent(s -> openInspector(v, s.name(),
                InspectorTab.initialFor(s.settingsCount() > 0, s.hasCustomUi())));
    }

    // ── Empty, no-match ────────────────────────────────────────────────────

    private void renderWaiting(BoardStatus status) {
        float y = centredBlockTop(radarSize() + ui.m().u(5) + paragraphHeight(2) + ui.m().chipHeight());
        float cx = ImGui.getWindowPosX() + ImGui.getWindowWidth() * 0.5f;
        ImDrawList draw = ImGui.getWindowDrawList();
        float after = radar(draw, cx, y, Icons.GAMEPAD);
        after = headline(draw, cx, after, "Waiting for a game client…");
        after = paragraph(draw, cx, after, "Launch RuneScape from the BotWithUs launcher. "
                + "Each client shows up here on its own within a few seconds.");
        steps(draw, cx, after + ui.m().u(2), status);
        reserveTo(after + ui.m().u(2) + ui.m().chipHeight());
    }

    private void renderNoMatch() {
        ImGuiTheme.Metrics m = ui.m();
        float y = centredBlockTop(paragraphHeight(2) + m.controlHeight());
        float cx = ImGui.getWindowPosX() + ImGui.getWindowWidth() * 0.5f;
        ImDrawList draw = ImGui.getWindowDrawList();
        float after = headline(draw, cx, y, "No clients match");
        String q = query.get().isBlank() ? "this view" : query.get();
        after = paragraph(draw, cx, after, "Nothing fits “" + q + "”.");
        String label = "Clear filter";
        float bw = ui.buttonWidth(null, label, Tone.GHOST);
        ImGui.setCursorScreenPos(cx - bw * 0.5f, after + m.u(3));
        if (ui.button("##clear-filter", null, label, Tone.GHOST, true)) {
            query.set("");
            view = View.ALL;
        }
        reserveTo(after + m.u(3) + m.controlHeight());
    }

    /**
     * The empty states are drawn on the draw list, which gives ImGui no content
     * size; this registers it, so a short window scrolls instead of clipping.
     */
    private void reserveTo(float bottomY) {
        ImGui.setCursorScreenPos(ImGui.getWindowPosX(), bottomY);
        ImGui.dummy(1f, ui.m().u(5));
    }

    /** Top of a vertically centred block, in screen space, moving with the scroll. */
    private float centredBlockTop(float blockH) {
        float origin = ImGui.getWindowPosY() - ImGui.getScrollY();
        float top = origin + (ImGui.getWindowHeight() - blockH) * 0.5f;
        return Math.max(origin + ui.m().u(5), top);
    }

    private float radarSize() {
        return ui.fonts().body().getFontSize() * RADAR_EM;
    }

    /** The waiting mark: two rings, a sweeping arc, and an icon. Returns the y below it. */
    private float radar(ImDrawList draw, float cx, float top, String icon) {
        float size = radarSize();
        float r = size * 0.5f;
        float cy = top + r;
        draw.addCircle(cx, cy, r, ImGuiTheme.COL_BORDER, 0, ui.m().hairline());
        draw.addCircle(cx, cy, r - ui.fonts().body().getFontSize() * RADAR_INNER_EM, ImGuiTheme.COL_BORDER,
                0, ui.m().hairline());
        float a0 = (float) ((ImGui.getTime() % RADAR_PERIOD_S) / RADAR_PERIOD_S * Math.PI * 2) - RADAR_SWEEP;
        draw.pathClear();
        draw.pathArcTo(cx, cy, r, a0, a0 + RADAR_SWEEP);
        draw.pathStroke(ImGuiTheme.COL_INFO, 0, RADAR_STROKE_PX);
        ImFont font = ui.fonts().titleMedium();
        ui.text(draw, font, cx - ui.width(font, icon) * 0.5f, cy - font.getFontSize() * 0.5f,
                ImGuiTheme.COL_FG2, icon);
        return top + size + ui.m().u(2) + ui.m().u(3);
    }

    private float headline(ImDrawList draw, float cx, float y, String text) {
        ImFont font = ui.fonts().bodyMedium();
        ui.text(draw, font, cx - ui.width(font, text) * 0.5f, y, ImGuiTheme.COL_FG, text);
        return y + font.getFontSize() * PARAGRAPH_LINE + ui.m().u(1);
    }

    private float paragraph(ImDrawList draw, float cx, float y, String text) {
        ImFont font = ui.fonts().small();
        float maxW = ui.width(font, ZERO) * PARAGRAPH_CH;
        return y + ui.centredParagraph(draw, font, cx, y, maxW, font.getFontSize() * PARAGRAPH_LINE,
                ImGuiTheme.COL_FG2, text);
    }

    private float paragraphHeight(int lines) {
        return ui.fonts().small().getFontSize() * PARAGRAPH_LINE * lines
                + ui.fonts().bodyMedium().getFontSize() * PARAGRAPH_LINE;
    }

    /** The two "what the host is doing" chips under the waiting message. */
    private void steps(ImDrawList draw, float cx, float y, BoardStatus status) {
        String[][] steps = {
                {"Scanning", status.pipePattern()},
                {"Auto-connect", status.autoConnect() ? "on" : "off"}};
        float gap = ui.m().u(2);
        float total = -gap;
        for (String[] s : steps) {
            total += stepWidth(s[0], s[1]) + gap;
        }
        float x = cx - total * 0.5f;
        for (String[] s : steps) {
            x += step(draw, x, y, s[0], s[1]) + gap;
        }
    }

    private float stepWidth(String label, String value) {
        return ui.m().u(2) * 2f + ui.width(ui.fonts().caption(), label) + ui.m().u(1.5f)
                + ui.width(ui.fonts().monoCaption(), value);
    }

    private float step(ImDrawList draw, float x, float y, String label, String value) {
        ImGuiTheme.Metrics m = ui.m();
        float w = stepWidth(label, value);
        float h = ui.fonts().caption().getFontSize() + m.u(1) * 2f;
        draw.addRectFilled(x, y, x + w, y + h, ImGuiTheme.COL_SURFACE, m.radiusSmall());
        draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radiusSmall());
        ui.textCentredY(draw, ui.fonts().caption(), x + m.u(2), y, h, ImGuiTheme.COL_FG2, label);
        float vx = x + m.u(2) + ui.width(ui.fonts().caption(), label) + m.u(1.5f);
        ui.textCentredY(draw, ui.fonts().monoCaption(), vx, y, h, ImGuiTheme.COL_FG, value);
        return w;
    }
}
