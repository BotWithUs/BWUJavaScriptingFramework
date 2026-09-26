package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.gui.CategoryStyle;
import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.Motion;
import com.botwithus.bot.cli.gui.usermode.board.ClientView;
import com.botwithus.bot.cli.gui.usermode.board.ScriptEntry;
import com.botwithus.bot.cli.gui.usermode.board.ScriptInfo;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionEntry;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionGroup;
import com.botwithus.bot.cli.gui.usermode.board.SubscriptionState;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The Start Script modal: search, category pills, a grouped list on the left and
 * the highlighted script's details on the right. Keyboard first — the search box
 * has focus on open, ↑↓ move, Enter starts, Esc closes.
 *
 * <p>Arrow keys are handled here rather than by ImGui's keyboard navigation
 * (the window opts out of it), so ↑↓ move the highlight while the cursor stays
 * in the search box.</p>
 *
 * <p>"Your subscriptions" sits above the local categories. A subscription that is
 * already installed is listed there only — its local row is hidden — and starts
 * the installed copy. One that is not installed stays open after Start while it
 * installs, shows its progress, and closes itself once the script is on the
 * client; a failed install stays open with the installer's message in the
 * details pane.</p>
 */
final class ScriptPickerPopup {

    /** What the user chose. */
    record Pick(String clientId, PickerRow row, boolean reviewSettings) {}

    private static final String POPUP_ID = "##start-script";
    private static final int QUERY_CAPACITY = 128;
    private static final float WIDTH_EM = 45.33f;
    private static final float HEIGHT_EM = 31.33f;
    private static final float SEARCH_HEIGHT_EM = 2.4f;
    private static final float ROW_TILE_EM = 1.867f;
    private static final float DETAIL_TILE_EM = 2.667f;
    private static final float DESC_CH = 40f;
    private static final float LINE = 1.35f;
    private static final float CHECKBOX_EM = 1.067f;
    private static final String ALL = "All";
    private static final String SUBSCRIPTIONS = "Subscriptions";

    private final Controls ui;
    private final SubscriptionRows subscriptionRows;
    private final ImString query = new ImString(QUERY_CAPACITY);
    private boolean pendingOpen;
    private boolean focusSearch;
    private boolean open;
    private String clientId;
    private String account;
    private List<ScriptEntry> catalog = List.of();
    private SubscriptionGroup group = new SubscriptionGroup.Pending();
    private String category = ALL;
    private int highlighted;
    private boolean scrollToHighlight;
    private boolean review;
    /** The subscription whose install this picker started and is waiting on, or {@code null}. */
    private String awaitingInstall;

    ScriptPickerPopup(Controls ui) {
        this.ui = ui;
        this.subscriptionRows = new SubscriptionRows(ui);
    }

    void open(ClientView client, List<ScriptEntry> scripts) {
        clientId = client.id();
        account = client.account();
        catalog = scripts.stream()
                .sorted(Comparator.comparing((ScriptEntry e) -> e.info().category().ordinal()))
                .toList();
        query.set("");
        category = ALL;
        highlighted = 0;
        review = false;
        awaitingInstall = null;
        pendingOpen = true;
    }

    boolean isOpen() {
        return open || pendingOpen;
    }

    /** Package-private: the dev preview's seam for highlighting a row, as ↑↓ would. */
    void highlight(int index) {
        highlighted = index;
    }

    /**
     * Renders the modal if open; returns the pick on the frame the user starts one.
     *
     * @param subscriptions the "Your subscriptions" group for a client id; asked every frame
     */
    Optional<Pick> render(Function<String, SubscriptionGroup> subscriptions) {
        if (pendingOpen) {
            ImGui.openPopup(POPUP_ID);
            pendingOpen = false;
            focusSearch = true;
        }
        placeWindow();
        pushModalStyle();
        int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoNavInputs | ImGuiWindowFlags.NoSavedSettings;
        open = ImGui.beginPopupModal(POPUP_ID, flags);
        popModalStyle();
        if (!open) {
            return Optional.empty();
        }
        if (clientId == null) {
            // ImGui still has this modal on its popup stack (e.g. this picker was
            // rebuilt while it was up) but nothing opened it here: close it rather
            // than draw a picker for no client.
            ImGui.closeCurrentPopup();
            ImGui.endPopup();
            open = false;
            return Optional.empty();
        }
        group = subscriptions.apply(clientId);
        followInstall();
        Optional<Pick> pick = renderContent();
        boolean closes = !open || pick.map(this::closesOnPick).orElse(false);
        if (closes) {
            ImGui.closeCurrentPopup();
            open = false;
            clientId = null;
        }
        ImGui.endPopup();
        return pick;
    }

    /**
     * A pick that starts something now closes the picker. One that starts an install
     * keeps it open, so the user sees it install, and {@link #followInstall} closes it
     * once the script is on the client.
     */
    private boolean closesOnPick(Pick pick) {
        return switch (pick.row()) {
            case PickerRow.Local ignored -> true;
            case PickerRow.Subscribed sub -> {
                if (!sub.entry().state().needsInstall()) {
                    yield true;
                }
                awaitingInstall = sub.entry().id();
                yield false;
            }
        };
    }

    /** Closes the picker once the install it started lands; stops waiting if it failed. */
    private void followInstall() {
        if (awaitingInstall == null) {
            return;
        }
        Optional<SubscriptionState> state = group.entries().stream()
                .filter(e -> e.id().equals(awaitingInstall))
                .map(SubscriptionEntry::state)
                .findFirst();
        if (state.isEmpty()) {
            awaitingInstall = null;
            return;
        }
        switch (state.get()) {
            case SubscriptionState.Installed ignored -> {
                awaitingInstall = null;
                open = false;
            }
            case SubscriptionState.Installing ignored -> { }
            // Still NotInstalled means the board has not marked it yet (the pick
            // was this frame); anything else ends the wait.
            case SubscriptionState.NotInstalled ignored -> { }
            case SubscriptionState.Failed ignored -> awaitingInstall = null;
        }
    }

    private void placeWindow() {
        var vp = ImGui.getMainViewport();
        float fs = ui.fonts().body().getFontSize();
        float margin = ui.m().u(5) * 2f;
        float w = Math.min(fs * WIDTH_EM, vp.getSizeX() - margin);
        float h = Math.min(fs * HEIGHT_EM, vp.getSizeY() - margin);
        ImGui.setNextWindowSize(w, h);
        ImGui.setNextWindowPos(vp.getPosX() + (vp.getSizeX() - w) * 0.5f, vp.getPosY() + (vp.getSizeY() - h) * 0.5f);
    }

    private void pushModalStyle() {
        ImGuiTheme.Metrics m = ui.m();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, m.radiusXl());
        ImGui.pushStyleVar(ImGuiStyleVar.PopupBorderSize, m.hairline());
        Controls.pushColor(ImGuiCol.PopupBg, ImGuiTheme.COL_ELEVATED);
        Controls.pushColor(ImGuiCol.Border, ImGuiTheme.COL_BORDER);
        Controls.pushColor(ImGuiCol.ModalWindowDimBg, ImGuiTheme.COL_SCRIM);
    }

    private static void popModalStyle() {
        ImGui.popStyleColor(3);
        ImGui.popStyleVar(3);
    }

    // ── Content ────────────────────────────────────────────────────────────

    private Optional<Pick> renderContent() {
        float x = ImGui.getWindowPosX();
        float y = ImGui.getWindowPosY();
        float w = ImGui.getWindowWidth();
        float h = ImGui.getWindowHeight();
        List<PickerRow> visible = filtered();
        highlighted = Math.max(0, Math.min(highlighted, visible.size() - 1));
        Optional<PickerRow> current = visible.isEmpty() ? Optional.empty() : Optional.of(visible.get(highlighted));
        boolean startByKey = handleKeys(visible.size());
        float headerH = renderHeader(x, y, w, visible);
        float footerH = ui.m().u(3) * 2f + ui.m().controlHeight();
        float bodyH = h - headerH - footerH;
        boolean startByRow = renderBody(visible, x, y + headerH, w, bodyH);
        boolean startByButton = renderFooter(current, x, y + h - footerH, w);
        boolean chosen = startByKey || startByRow || startByButton;
        if (chosen && current.isPresent() && current.get().isChoosable()) {
            return Optional.of(new Pick(clientId, current.get(), review));
        }
        return Optional.empty();
    }

    private boolean handleKeys(int count) {
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            open = false;
            return false;
        }
        if (ImGui.isKeyPressed(ImGuiKey.DownArrow)) {
            highlighted = Math.min(count - 1, highlighted + 1);
            scrollToHighlight = true;
        }
        if (ImGui.isKeyPressed(ImGuiKey.UpArrow)) {
            highlighted = Math.max(0, highlighted - 1);
            scrollToHighlight = true;
        }
        return ImGui.isKeyPressed(ImGuiKey.Enter, false) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter, false);
    }

    /** Subscriptions first, then local scripts less any that a subscription row already stands for. */
    private List<PickerRow> filtered() {
        String q = query.get().strip().toLowerCase(Locale.ROOT);
        List<PickerRow> out = new ArrayList<>();
        if (showsSubscriptions()) {
            for (SubscriptionEntry s : group.entries()) {
                PickerRow row = new PickerRow.Subscribed(s);
                if (row.matches(q)) {
                    out.add(row);
                }
            }
        }
        if (SUBSCRIPTIONS.equals(category)) {
            return out;
        }
        for (ScriptEntry e : localRows()) {
            boolean inCategory = ALL.equals(category) || e.info().categoryLabel().equals(category);
            PickerRow row = new PickerRow.Local(e);
            if (inCategory && row.matches(q)) {
                out.add(row);
            }
        }
        return out;
    }

    private boolean showsSubscriptions() {
        return ALL.equals(category) || SUBSCRIPTIONS.equals(category);
    }

    /** The local catalogue without the scripts listed under "Your subscriptions", so none shows twice. */
    private List<ScriptEntry> localRows() {
        Set<Integer> claimed = new HashSet<>();
        for (SubscriptionEntry s : group.entries()) {
            s.localKey().ifPresent(claimed::add);
        }
        return catalog.stream().filter(e -> !claimed.contains(e.key())).toList();
    }

    private float renderHeader(float x, float y, float w, List<PickerRow> visible) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float left = x + m.u(4);
        float inner = w - m.u(4) * 2f;
        float rowY = y + m.u(4);
        float rowH = m.controlHeight();
        ImFont title = ui.fonts().bodyMedium();
        String heading = "Start a script";
        ui.textCentredY(draw, title, left, rowY, rowH, ImGuiTheme.COL_FG, heading);
        String on = " on " + account;
        float onX = left + ui.width(title, heading);
        ui.textCentredY(draw, ui.fonts().body(), onX, rowY, rowH, ImGuiTheme.COL_FG2,
                ui.ellipsize(ui.fonts().body(), on, inner - ui.width(title, heading) - rowH - m.u(2)));
        ImGui.setCursorScreenPos(x + w - m.u(4) - rowH, rowY);
        if (ui.button("##picker-close", Icons.XMARK, "", Tone.ICON, true)) {
            open = false;
        }
        float searchY = rowY + rowH + m.u(3);
        float searchH = ui.fonts().body().getFontSize() * SEARCH_HEIGHT_EM;
        ImGui.setCursorScreenPos(left, searchY);
        int total = group.entries().size() + localRows().size();
        if (ui.searchBox("##picker-search", query, "Search " + total + " scripts", inner, searchH,
                focusSearch)) {
            highlighted = 0;
        }
        focusSearch = false;
        float pillsY = searchY + searchH + m.u(3);
        renderPills(left, pillsY, inner);
        return pillsY + m.controlSmallHeight() + m.u(3) - y;
    }

    private void renderPills(float x, float y, float maxW) {
        Set<String> labels = new LinkedHashSet<>();
        labels.add(ALL);
        if (!group.entries().isEmpty()) {
            labels.add(SUBSCRIPTIONS);
        }
        for (ScriptEntry e : localRows()) {
            labels.add(e.info().categoryLabel());
        }
        float px = x;
        for (String label : labels) {
            float pw = pillWidth(label);
            if (px + pw > x + maxW) {
                break;
            }
            if (pill(label, px, y, pw)) {
                category = label;
                highlighted = 0;
            }
            px += pw + ui.m().u(1.5f);
        }
    }

    private float pillWidth(String label) {
        return ui.width(ui.fonts().captionMedium(), label) + ui.m().u(2.5f) * 2f;
    }

    private boolean pill(String label, float x, float y, float w) {
        ImGuiTheme.Metrics m = ui.m();
        float h = m.controlSmallHeight();
        ImGui.setCursorScreenPos(x, y);
        boolean clicked = ImGui.invisibleButton("##pill-" + label, w, h);
        float t = Motion.step("pill:" + label, ImGui.isItemHovered() ? 1f : 0f, 1f / ImGuiTheme.DURATION_FAST_S);
        boolean on = label.equals(category);
        ImDrawList draw = ImGui.getWindowDrawList();
        int bg = on ? ImGuiTheme.COL_FG : Controls.scaleAlpha(ImGuiTheme.COL_SURFACE, t);
        int border = on ? ImGuiTheme.COL_FG : ImGuiTheme.COL_BORDER;
        int fg = on ? ImGuiTheme.COL_BG : Controls.lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_FG, t);
        draw.addRectFilled(x, y, x + w, y + h, bg, h * 0.5f);
        draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, border, h * 0.5f);
        ui.textCentredY(draw, ui.fonts().captionMedium(), x + m.u(2.5f), y, h, fg, label);
        return clicked;
    }

    // ── Body: list + details ───────────────────────────────────────────────

    private boolean renderBody(List<PickerRow> visible, float x, float y, float w, float h) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float half = w * 0.5f;
        draw.addLine(x, y + 0.5f, x + w, y + 0.5f, ImGuiTheme.COL_BORDER, ui.m().hairline());
        draw.addLine(x + half - 0.5f, y, x + half - 0.5f, y + h, ImGuiTheme.COL_BORDER, ui.m().hairline());
        ImGui.setCursorScreenPos(x, y + 1f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, ui.m().u(2), ui.m().u(2));
        ImGui.beginChild("##picker-list", half - 1f, h - 1f, ImGuiChildFlags.AlwaysUseWindowPadding, 0);
        boolean start = renderList(visible);
        ImGui.endChild();
        ImGui.popStyleVar();
        if (!visible.isEmpty()) {
            ImGui.setCursorScreenPos(x + half, y + 1f);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, ui.m().u(4), ui.m().u(4));
            ImGui.beginChild("##picker-details", half, h - 1f, ImGuiChildFlags.AlwaysUseWindowPadding, 0);
            ImGui.popStyleVar();
            renderDetails(visible.get(highlighted));
            ImGui.endChild();
        }
        return start;
    }

    private boolean renderList(List<PickerRow> visible) {
        boolean subsSection = renderSubscriptionsHeader(visible);
        if (visible.isEmpty()) {
            if (!subsSection) {
                renderNoMatch();
            }
            return false;
        }
        boolean start = false;
        String localGroup = null;
        for (int i = 0; i < visible.size(); i++) {
            PickerRow row = visible.get(i);
            switch (row) {
                case PickerRow.Local local -> {
                    String label = local.entry().info().categoryLabel();
                    if (ALL.equals(category) && !label.equals(localGroup)) {
                        localGroup = label;
                        groupHeader(localGroup, i == 0 && !subsSection);
                    }
                }
                case PickerRow.Subscribed ignored -> { }
            }
            start |= row(i, row);
        }
        return start;
    }

    /**
     * Draws the "Your subscriptions" heading and its note, when the group has
     * something to say here. Returns whether it drew anything.
     */
    private boolean renderSubscriptionsHeader(List<PickerRow> visible) {
        if (!showsSubscriptions() || group.isHidden()) {
            return false;
        }
        boolean hasRows = !visible.isEmpty() && visible.get(0).isSubscription();
        Optional<SubscriptionRows.Note> note = SubscriptionRows.noteFor(group);
        boolean searching = !query.get().isBlank();
        if (!hasRows && (searching || note.isEmpty())) {
            return false;
        }
        groupHeader(SubscriptionRows.GROUP_LABEL, true);
        note.ifPresent(this::noteLine);
        return true;
    }

    private void noteLine(SubscriptionRows.Note note) {
        ImGuiTheme.Metrics m = ui.m();
        float x = ImGui.getCursorScreenPosX() + m.u(2);
        float y = ImGui.getCursorScreenPosY();
        float w = ImGui.getContentRegionAvailX() - m.u(2) * 2f;
        float h = subscriptionRows.note(ImGui.getWindowDrawList(), note, x, y, w);
        ImGui.dummy(1f, h + m.u(1) - ImGui.getStyle().getItemSpacingY());
    }

    private void groupHeader(String label, boolean first) {
        ImGuiTheme.Metrics m = ui.m();
        float top = first ? m.u(1) : m.u(3);
        ImFont font = ui.fonts().captionMedium();
        float x = ImGui.getCursorScreenPosX() + m.u(2);
        float y = ImGui.getCursorScreenPosY() + top;
        ui.text(ImGui.getWindowDrawList(), font, x, y, ImGuiTheme.COL_FG3, label.toUpperCase(Locale.ROOT));
        ImGui.dummy(1f, top + font.getFontSize() + m.u(2) - ImGui.getStyle().getItemSpacingY());
    }

    private boolean row(int index, PickerRow pickerRow) {
        ImGuiTheme.Metrics m = ui.m();
        float tile = ui.fonts().body().getFontSize() * ROW_TILE_EM;
        float w = ImGui.getContentRegionAvailX();
        float h = tile + m.u(2) * 2f;
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImGui.invisibleButton("##row" + index, w, h);
        boolean clicked = ImGui.isItemClicked();
        boolean doubleClicked = ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0);
        boolean on = index == highlighted;
        if (clicked) {
            highlighted = index;
        }
        if (on && scrollToHighlight) {
            ImGui.setScrollHereY();
            scrollToHighlight = false;
        }
        float t = Motion.step("prow:" + index, ImGui.isItemHovered() ? 1f : 0f, 1f / ImGuiTheme.DURATION_FAST_S);
        ImDrawList draw = ImGui.getWindowDrawList();
        paintRowBackground(draw, x, y, w, h, on, t);
        switch (pickerRow) {
            case PickerRow.Local local -> paintRow(draw, local.entry().info(), x, y, w, h, tile, on);
            case PickerRow.Subscribed sub -> subscriptionRows.paintRow(draw, sub.entry(), x, y, w, h, tile, on);
        }
        return doubleClicked;
    }

    private void paintRowBackground(ImDrawList draw, float x, float y, float w, float h, boolean on, float hoverT) {
        ImGuiTheme.Metrics m = ui.m();
        if (on) {
            draw.addRectFilled(x, y, x + w, y + h, ImGuiTheme.COL_BG, m.radius());
            draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m.radius());
        } else if (hoverT > 0.01f) {
            draw.addRectFilled(x, y, x + w, y + h, Controls.scaleAlpha(ImGuiTheme.COL_SURFACE, hoverT), m.radius());
        }
    }

    private void paintRow(ImDrawList draw, ScriptInfo s, float x, float y, float w, float h, float tile,
                          boolean on) {
        ImGuiTheme.Metrics m = ui.m();
        String icon = CategoryStyle.of(s.category()).icon();
        ui.iconTile(draw, x + m.u(2), y + m.u(2), tile, icon, on ? ImGuiTheme.COL_ACCENT : ImGuiTheme.COL_FG2,
                on ? ImGuiTheme.COL_ACCENT_SOFT : ImGuiTheme.COL_ELEVATED);
        float tx = x + m.u(2) + tile + m.u(3);
        float tw = x + w - m.u(2) - tx;
        ImFont name = ui.fonts().smallMedium();
        ImFont meta = ui.fonts().caption();
        float textH = name.getFontSize() * LINE + meta.getFontSize() * LINE;
        float ty = y + (h - textH) * 0.5f;
        ui.text(draw, name, tx, ty, ImGuiTheme.COL_FG, ui.ellipsize(name, s.name(), tw));
        String sub = (s.author().isBlank() ? "" : s.author() + " · ") + "v" + s.version();
        ui.text(draw, meta, tx, ty + name.getFontSize() * LINE, ImGuiTheme.COL_FG2, ui.ellipsize(meta, sub, tw));
    }

    private void renderNoMatch() {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float cx = ImGui.getCursorScreenPosX() + ImGui.getContentRegionAvailX() * 0.5f;
        float y = ImGui.getCursorScreenPosY() + m.u(6);
        ImFont font = ui.fonts().small();
        String first = catalog.isEmpty() && group.entries().isEmpty()
                ? "No scripts installed yet." : "No scripts match “" + query.get() + "”.";
        ui.text(draw, font, cx - ui.width(font, first) * 0.5f, y, ImGuiTheme.COL_FG2, first);
        String second = "Installed scripts live in scripts/.";
        ui.text(draw, font, cx - ui.width(font, second) * 0.5f, y + font.getFontSize() * LINE,
                ImGuiTheme.COL_FG2, second);
    }

    private void renderDetails(PickerRow row) {
        switch (row) {
            case PickerRow.Local local -> renderDetails(local.entry().info());
            case PickerRow.Subscribed sub -> renderSubscriptionDetails(sub.entry());
        }
    }

    private void renderSubscriptionDetails(SubscriptionEntry s) {
        float left = ImGui.getCursorScreenPosX();
        float top = ImGui.getCursorScreenPosY();
        float inner = ImGui.getContentRegionAvailX();
        float tile = ui.fonts().body().getFontSize() * DETAIL_TILE_EM;
        float bottom = subscriptionRows.details(ImGui.getWindowDrawList(), s, account, left, top, inner, tile);
        ImGui.setCursorScreenPos(left, top);
        ImGui.dummy(inner, bottom - top);
    }

    /**
     * The highlighted script's details, laid out top-down in a scrolling child so a
     * short window scrolls rather than overlapping rows. The review checkbox sits at
     * the bottom when there is room, and right after the details when there is not.
     */
    private void renderDetails(ScriptInfo s) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        float left = ImGui.getCursorScreenPosX();
        float top = ImGui.getCursorScreenPosY();
        float inner = ImGui.getContentRegionAvailX();
        float cy = top;
        float tile = ui.fonts().body().getFontSize() * DETAIL_TILE_EM;
        ui.iconTile(draw, left, cy, tile, CategoryStyle.of(s.category()).icon(), ImGuiTheme.COL_ACCENT,
                ImGuiTheme.COL_ACCENT_SOFT);
        cy += tile + m.u(3);
        ImFont title = ui.fonts().bodyMedium();
        ui.text(draw, title, left, cy, ImGuiTheme.COL_FG, ui.ellipsize(title, s.name(), inner));
        cy += title.getFontSize() * LINE + m.u(3);
        ImFont small = ui.fonts().small();
        float descW = Math.min(inner, ui.width(small, "0") * DESC_CH);
        for (String line : ui.wrap(small, s.description().isBlank() ? "No description." : s.description(), descW)) {
            ui.text(draw, small, left, cy, ImGuiTheme.COL_FG2, line);
            cy += small.getFontSize() * LINE;
        }
        cy = KeyValueList.draw(ui, draw, facts(s), left, cy + m.u(3));
        if (s.settingsCount() > 0) {
            float bottom = ImGui.getWindowPosY() + ImGui.getWindowHeight() - m.u(4) - m.controlHeight();
            float checkY = Math.max(cy + m.u(3), bottom);
            renderReviewCheck(left, checkY, inner);
            cy = checkY + m.controlHeight();
        }
        ImGui.setCursorScreenPos(left, top);
        ImGui.dummy(inner, cy - top);
    }

    private static List<KeyValueList.Row> facts(ScriptInfo s) {
        return List.of(
                new KeyValueList.Row("Author", s.author().isBlank() ? "—" : s.author()),
                new KeyValueList.Row("Version", s.version()),
                new KeyValueList.Row("Category", s.categoryLabel()),
                new KeyValueList.Row("Settings", s.settingsCount() > 0 ? s.settingsCount() + " fields" : "none"),
                new KeyValueList.Row("Custom UI", s.hasCustomUi() ? "yes" : "no"));
    }

    private void renderReviewCheck(float x, float y, float w) {
        ImGuiTheme.Metrics m = ui.m();
        float h = m.controlHeight();
        ImGui.setCursorScreenPos(x, y);
        if (ImGui.invisibleButton("##review", w, h)) {
            review = !review;
        }
        ImDrawList draw = ImGui.getWindowDrawList();
        float box = ui.fonts().body().getFontSize() * CHECKBOX_EM;
        float by = y + (h - box) * 0.5f;
        draw.addRectFilled(x, by, x + box, by + box, review ? ImGuiTheme.COL_ACCENT : ImGuiTheme.COL_BG,
                m.radiusSmall());
        draw.addRect(x + 0.5f, by + 0.5f, x + box - 0.5f, by + box - 0.5f,
                review ? ImGuiTheme.COL_ACCENT : ImGuiTheme.COL_BORDER, m.radiusSmall());
        if (review) {
            ImFont cap = ui.fonts().caption();
            ui.text(draw, cap, x + (box - ui.width(cap, Icons.CHECK)) * 0.5f, by + (box - cap.getFontSize()) * 0.5f,
                    ImGuiTheme.COL_ON_ACCENT, Icons.CHECK);
        }
        ui.textCentredY(draw, ui.fonts().small(), x + box + m.u(2), y, h, ImGuiTheme.COL_FG,
                "Review settings after starting");
    }

    // ── Footer ─────────────────────────────────────────────────────────────

    /** The primary button's face: what Start will do for the highlighted row. */
    private record StartFace(String icon, String label, boolean enabled) {}

    private static StartFace startFace(Optional<PickerRow> current) {
        if (current.isEmpty()) {
            return new StartFace(Icons.PLAY, "Start", false);
        }
        return switch (current.get()) {
            case PickerRow.Local ignored -> new StartFace(Icons.PLAY, "Start", true);
            case PickerRow.Subscribed sub -> switch (sub.entry().state()) {
                case SubscriptionState.Installed ignored -> new StartFace(Icons.PLAY, "Start", true);
                case SubscriptionState.NotInstalled ignored -> new StartFace(Icons.DOWNLOAD, "Install & start", true);
                case SubscriptionState.Failed ignored -> new StartFace(Icons.DOWNLOAD, "Install & start", true);
                case SubscriptionState.Installing ignored -> new StartFace(Icons.DOWNLOAD, "Installing…", false);
            };
        };
    }

    private boolean renderFooter(Optional<PickerRow> current, float x, float y, float w) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addLine(x, y + 0.5f, x + w, y + 0.5f, ImGuiTheme.COL_BORDER, m.hairline());
        float rowY = y + m.u(3);
        float rowH = m.controlHeight();
        float kx = x + m.u(4);
        kx = keyHint(draw, kx, rowY, rowH, "↑↓", "move");
        kx = keyHint(draw, kx, rowY, rowH, "Enter", "start");
        keyHint(draw, kx, rowY, rowH, "Esc", "close");
        StartFace face = startFace(current);
        float startW = ui.buttonWidth(face.icon(), face.label(), Tone.PRIMARY);
        float cancelW = ui.buttonWidth(null, "Cancel", Tone.GHOST);
        float bx = x + w - m.u(4) - startW - m.u(2) - cancelW;
        ImGui.setCursorScreenPos(bx, rowY);
        if (ui.button("##picker-cancel", null, "Cancel", Tone.GHOST, true)) {
            open = false;
        }
        ImGui.setCursorScreenPos(bx + cancelW + m.u(2), rowY);
        return ui.button("##picker-start", face.icon(), face.label(), Tone.PRIMARY, face.enabled());
    }

    private float keyHint(ImDrawList draw, float x, float y, float h, String key, String label) {
        ImGuiTheme.Metrics m = ui.m();
        float kh = ui.kbdHeight();
        ui.kbd(draw, x, y + (h - kh) * 0.5f, key);
        float lx = x + ui.kbdWidth(key) + m.u(1.25f);
        ui.textCentredY(draw, ui.fonts().caption(), lx, y, h, ImGuiTheme.COL_FG2, label);
        return lx + ui.width(ui.fonts().caption(), label) + m.u(3);
    }
}
