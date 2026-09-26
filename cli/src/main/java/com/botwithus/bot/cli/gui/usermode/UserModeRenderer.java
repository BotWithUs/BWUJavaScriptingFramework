package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Segment;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.Motion;
import com.botwithus.bot.cli.gui.usermode.ClientFilter.View;
import com.botwithus.bot.cli.gui.usermode.board.BoardStatus;
import com.botwithus.bot.cli.gui.usermode.board.ClientBoard;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;
import com.botwithus.bot.cli.gui.usermode.board.InspectorTarget;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Clients page — Normal mode's only screen, and the first thing Advanced
 * mode will show once its sidebar is redesigned. A page header with counts and
 * the view filter, a responsive grid of {@link ClientCard}s, the empty and
 * host-offline states, and the docked config inspector that pushes the grid
 * aside when open.
 */
public class UserModeRenderer {

    private static final int QUERY_CAPACITY = 64;
    private static final float PARAGRAPH_CH = 34f;
    private static final float PARAGRAPH_LINE = 1.45f;
    private static final float RADAR_EM = 4.8f;
    private static final float RADAR_INNER_EM = 0.8f;
    private static final float RADAR_PERIOD_S = 1.6f;
    private static final float RADAR_SWEEP = (float) (Math.PI / 2);
    private static final float RADAR_OFF_ANGLE = (float) (-Math.PI / 4);
    private static final float RADAR_STROKE_PX = 2f;
    private static final float REVIEW_WAIT_S = 3f;
    private static final String ZERO = "0";
    private static final int PAGE_COLOR_COUNT = 5;
    /** The filter box is 200 px where a card is 290 px at the base size. */
    private static final float SEARCH_WIDTH_OF_CARD = 200f / 290f;

    private final Controls ui;
    private final ClientCard card;
    private final ScriptPickerPopup picker;
    private final ConfigInspector inspector;

    private final ImString query = new ImString(QUERY_CAPACITY);
    private final Map<String, Double> firstSeen = new HashMap<>();
    private View view = View.ALL;
    private String selectedId;
    private float drawerProgress;
    private InspectorTarget lastTarget;
    private String pendingReview;
    private double pendingReviewUntil;

    public UserModeRenderer(Controls ui) {
        this.ui = ui;
        this.card = new ClientCard(ui);
        this.picker = new ScriptPickerPopup(ui);
        this.inspector = new ConfigInspector(ui);
    }

    /** Opens the picker for {@code clientId}, as its card's "Start script" would. */
    public void openPicker(ClientBoard board, String clientId) {
        board.clients().stream().filter(c -> c.id().equals(clientId)).findFirst()
                .ifPresent(c -> picker.open(c, board.catalog()));
    }

    /** Opens the inspector on {@code clientId}'s running script, on the given tab. */
    public void openInspector(String clientId, boolean scriptUiTab) {
        inspector.open(clientId, scriptUiTab);
        selectedId = clientId;
    }

    /** Selects a view segment, as clicking it would. Package-private: the dev preview's seam. */
    void showView(View next) {
        view = next;
    }

    /** Package-private: the dev preview's seam for staging an edit in the inspector. */
    ConfigInspector inspector() {
        return inspector;
    }

    public void render(ClientBoard board) {
        pushPageColors();
        float availW = ImGui.getContentRegionAvailX();
        float availH = ImGui.getContentRegionAvailY();
        Optional<InspectorTarget> target = resolveInspector(board);
        float drawerFull = ui.m().drawerWidth(availW);
        float drawerW = animateDrawer(target.isPresent()) * drawerFull;

        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.beginChild("##clients-page", availW - drawerW, availH, false,
                ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar();
        renderPage(board);
        ImGui.endChild();

        if (drawerW > 1f && lastTarget != null) {
            ImGui.sameLine(0f, 0f);
            renderDrawer(drawerW, drawerFull, availH);
        }
        picker.render().ifPresent(pick -> {
            board.actions().startScript(pick.clientId(), pick.entry());
            if (pick.reviewSettings()) {
                pendingReview = pick.clientId();
                pendingReviewUntil = ImGui.getTime() + REVIEW_WAIT_S;
            }
        });
        handleEscape();
        ImGui.popStyleColor(PAGE_COLOR_COUNT);
        ImGui.popStyleVar();
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

    // ── Drawer ─────────────────────────────────────────────────────────────

    private Optional<InspectorTarget> resolveInspector(ClientBoard board) {
        if (pendingReview != null) {
            if (board.inspect(pendingReview).isPresent()) {
                openInspector(pendingReview, false);
                pendingReview = null;
            } else if (ImGui.getTime() > pendingReviewUntil) {
                pendingReview = null;
            }
        }
        if (!inspector.isOpen()) {
            return Optional.empty();
        }
        Optional<InspectorTarget> target = board.inspect(inspector.clientId());
        if (target.isEmpty() || target.get().isGone().getAsBoolean()) {
            inspector.close();
            return Optional.empty();
        }
        lastTarget = target.get();
        return target;
    }

    private float animateDrawer(boolean open) {
        float step = ImGui.getIO().getDeltaTime() / ImGuiTheme.DURATION_S;
        drawerProgress = Math.max(0f, Math.min(1f, drawerProgress + (open ? step : -step)));
        return Motion.easeOutCubic(drawerProgress);
    }

    private void renderDrawer(float drawerW, float drawerFull, float h) {
        Controls.pushColor(ImGuiCol.ChildBg, ImGuiTheme.COL_SURFACE);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.beginChild("##inspector-drawer", drawerW, h, false,
                ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar();
        ImGui.popStyleColor();
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getWindowPosX();
        float y = ImGui.getWindowPosY();
        inspector.render(lastTarget, drawerFull, h);
        draw.addLine(x + 0.5f, y, x + 0.5f, y + h, ImGuiTheme.COL_BORDER, ui.m().hairline());
        ImGui.endChild();
    }

    private void handleEscape() {
        if (picker.isOpen() || !inspector.isOpen() || ImGui.isAnyItemActive()) {
            return;
        }
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            inspector.close();
        }
    }

    // ── Page ───────────────────────────────────────────────────────────────

    private void renderPage(ClientBoard board) {
        List<ClientView> clients = board.clients();
        BoardStatus status = board.status();
        boolean offline = status.hostOffline();
        float headerH = renderHeader(clients, offline);
        ImGuiTheme.Metrics m = ui.m();
        ImGui.setCursorPos(0f, headerH);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m.u(5), m.u(1));
        ImGui.beginChild("##clients-grid", 0f, 0f, ImGuiChildFlags.AlwaysUseWindowPadding, 0);
        ImGui.popStyleVar();
        if (offline) {
            renderOffline(board, status);
        } else if (clients.isEmpty()) {
            renderWaiting(status);
        } else {
            renderGrid(board, ClientFilter.apply(clients, view, query.get()));
        }
        ImGui.endChild();
    }

    /** Title, counts and the filter row. Returns the header's height. */
    private float renderHeader(List<ClientView> clients, boolean offline) {
        ImGuiTheme.Metrics m = ui.m();
        float rowH = m.controlHeight();
        float x0 = ImGui.getWindowPosX() + m.u(5);
        float y0 = ImGui.getWindowPosY() + m.u(4);
        float right = ImGui.getWindowPosX() + ImGui.getWindowWidth() - m.u(5);
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont title = ui.fonts().titleMedium();
        ui.textCentredY(draw, title, x0, y0, rowH, ImGuiTheme.COL_FG, "Clients");
        long running = clients.stream().filter(c -> c.status().isRunning()).count();
        if (!clients.isEmpty() && !offline) {
            String count = clients.size() + " connected · " + running + " running";
            ui.textCentredY(draw, ui.fonts().monoCaption(), x0 + ui.width(title, "Clients") + m.u(3), y0, rowH,
                    ImGuiTheme.COL_FG2, count);
            renderFilterRow(clients, (int) running, right, y0, rowH);
        }
        return m.u(4) + rowH + m.u(3);
    }

    private void renderFilterRow(List<ClientView> clients, int running, float right, float y, float rowH) {
        ImGuiTheme.Metrics m = ui.m();
        long attention = clients.stream().filter(c -> c.status().needsAttention()).count();
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
        if (clients.size() > ClientFilter.SEARCH_THRESHOLD) {
            float searchW = m.cardMinWidth() * SEARCH_WIDTH_OF_CARD;
            ImGui.setCursorScreenPos(segX - m.u(3) - searchW, y);
            ui.searchBox("##client-filter", query, "Filter by account or script", searchW, rowH, false);
        }
    }

    // ── Grid ───────────────────────────────────────────────────────────────

    private void renderGrid(ClientBoard board, List<ClientView> visible) {
        if (visible.isEmpty()) {
            renderNoMatch();
            return;
        }
        ImGuiTheme.Metrics m = ui.m();
        float availW = ImGui.getContentRegionAvailX();
        float gap = m.u(3);
        int columns = Math.max(1, (int) ((availW + gap) / (m.cardMinWidth() + gap)));
        float cardW = (availW - gap * (columns - 1)) / columns;
        float cardH = card.height();
        float originX = ImGui.getCursorScreenPosX();
        float originY = ImGui.getCursorScreenPosY();
        for (int i = 0; i < visible.size(); i++) {
            ClientView v = visible.get(i);
            float x = originX + (i % columns) * (cardW + gap);
            float y = originY + (float) (i / columns) * (cardH + gap);
            ClientCard.Intent intent = card.render(v, x, y, cardW, v.id().equals(selectedId), appear(v.id()),
                    board.actions());
            handleIntent(board, v, intent);
        }
        int rows = (visible.size() + columns - 1) / columns;
        ImGui.setCursorScreenPos(originX, originY);
        ImGui.dummy(availW, rows * cardH + (rows - 1) * gap + m.u(5));
    }

    private float appear(String id) {
        double now = ImGui.getTime();
        double since = now - firstSeen.computeIfAbsent(id, k -> now);
        return (float) Math.min(1.0, since / ImGuiTheme.DURATION_S);
    }

    private void handleIntent(ClientBoard board, ClientView v, ClientCard.Intent intent) {
        switch (intent) {
            case NONE -> { }
            case SELECT -> selectedId = v.id();
            case START_SCRIPT -> {
                selectedId = v.id();
                picker.open(v, board.catalog());
            }
            case CONFIGURE -> openInspector(v.id(), false);
        }
    }

    // ── Empty, offline, no-match ───────────────────────────────────────────

    private void renderWaiting(BoardStatus status) {
        float y = centredBlockTop(radarSize() + ui.m().u(5) + paragraphHeight(2) + ui.m().chipHeight());
        float cx = ImGui.getWindowPosX() + ImGui.getWindowWidth() * 0.5f;
        ImDrawList draw = ImGui.getWindowDrawList();
        float after = radar(draw, cx, y, Icons.GAMEPAD, false);
        after = headline(draw, cx, after, "Waiting for a game client…");
        after = paragraph(draw, cx, after, "Start RuneScape from the BotWithUs launcher. "
                + "Clients show up here on their own, usually within a few seconds.");
        steps(draw, cx, after + ui.m().u(2), status);
        reserveTo(after + ui.m().u(2) + ui.m().chipHeight());
    }

    private void renderOffline(ClientBoard board, BoardStatus status) {
        ImGuiTheme.Metrics m = ui.m();
        float y = centredBlockTop(radarSize() + m.u(5) + paragraphHeight(2) + m.controlHeight());
        float cx = ImGui.getWindowPosX() + ImGui.getWindowWidth() * 0.5f;
        ImDrawList draw = ImGui.getWindowDrawList();
        float after = radar(draw, cx, y, Icons.LINK_SLASH, true);
        after = headline(draw, cx, after, "Lost the connection to the agent");
        String attempts = status.gaveUpAttempts() > 0
                ? "We gave up after " + status.gaveUpAttempts() + " attempts. "
                : "We gave up reconnecting. ";
        after = paragraph(draw, cx, after, attempts + "Your scripts in the game clients may still be running.");
        String label = "Try again";
        float bw = ui.buttonWidth(Icons.REDO, label, Tone.PRIMARY);
        ImGui.setCursorScreenPos(cx - bw * 0.5f, after + m.u(3));
        if (ui.button("##retry-host", Icons.REDO, label, Tone.PRIMARY, true)) {
            board.actions().retryHost();
        }
        reserveTo(after + m.u(3) + m.controlHeight());
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

    /** The waiting / offline mark: two rings, an arc, and an icon. Returns the y below it. */
    private float radar(ImDrawList draw, float cx, float top, String icon, boolean off) {
        float size = radarSize();
        float r = size * 0.5f;
        float cy = top + r;
        draw.addCircle(cx, cy, r, ImGuiTheme.COL_BORDER, 0, ui.m().hairline());
        draw.addCircle(cx, cy, r - ui.fonts().body().getFontSize() * RADAR_INNER_EM, ImGuiTheme.COL_BORDER,
                0, ui.m().hairline());
        float a0 = off ? RADAR_OFF_ANGLE - RADAR_SWEEP * 0.5f
                : (float) ((ImGui.getTime() % RADAR_PERIOD_S) / RADAR_PERIOD_S * Math.PI * 2) - RADAR_SWEEP;
        draw.pathClear();
        draw.pathArcTo(cx, cy, r, a0, a0 + RADAR_SWEEP);
        draw.pathStroke(off ? ImGuiTheme.COL_DANGER : ImGuiTheme.COL_INFO, 0, RADAR_STROKE_PX);
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
