package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.nav.PageId;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiPopupFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.Optional;

/**
 * The drawer the shell docks on the right of the page body, in both modes. It
 * slides open beside the page that owns the open script and pushes that page
 * aside rather than covering it. Its left edge is a grip: drag it to resize the
 * drawer, double-click it to go back to the default width. Render thread only.
 *
 * <p>Each frame the shell calls {@link #beginFrame} before drawing the page, to
 * learn how much width to leave, and {@link #render} after it. Once the shell's
 * own window has ended it calls {@link #renderWindows} for the popped-out
 * script UIs.</p>
 */
public final class InspectorDock {

    private static final int POPUP_ANY = ImGuiPopupFlags.AnyPopupId | ImGuiPopupFlags.AnyPopupLevel;

    private final Controls ui;
    private final InspectorState state;
    private final InspectorSource source;
    private final ConfigInspector inspector;
    private final ScriptUiWindows windows;
    private float progress;
    private InspectorTarget lastTarget;
    /** The width the user dragged the drawer to, or {@code 0} until they do. */
    private float preferredWidth;

    public InspectorDock(Controls ui, InspectorState state, InspectorSource source) {
        this.ui = ui;
        this.state = state;
        this.source = source;
        this.inspector = new ConfigInspector(ui, state);
        this.windows = new ScriptUiWindows(ui, state, source);
    }

    public InspectorState state() {
        return state;
    }

    /** The open form, or {@code null} before its first frame. The dev preview's seam. */
    ConfigEdits edits() {
        return inspector.edits();
    }

    /**
     * Resolves the open script, handles Esc and advances the slide.
     *
     * @param shown      the page drawn this frame; the drawer opens only beside the script's own page
     * @param availWidth the width the page and drawer share
     * @return the width to leave for the drawer this frame
     */
    public float beginFrame(PageId shown, float availWidth) {
        handleEscape(shown);
        Optional<InspectorTarget> target = state.docksBeside(shown) ? state.resolve(source) : Optional.empty();
        target.ifPresent(t -> lastTarget = t);
        progress = ui.motion().advance(progress, target.isPresent(), ImGuiTheme.DURATION_S);
        float width = ui.motion().ease(progress) * fullWidth(availWidth);
        return lastTarget != null && width > 1f ? width : 0f;
    }

    /**
     * Draws the grip and the drawer beside it at {@code width} (possibly
     * mid-slide) and full height {@code h}.
     */
    public void render(float width, float availWidth, float h) {
        if (lastTarget == null || width <= 0f) {
            return;
        }
        float gripW = Math.min(ui.m().drawerGrip(), width);
        grip(gripW, availWidth, h);
        float bodyW = width - gripW;
        if (bodyW <= 0f) {
            return;
        }
        ImGui.sameLine(0f, 0f);
        Controls.pushColor(ImGuiCol.ChildBg, ImGuiTheme.COL_SURFACE);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.beginChild("##inspector-drawer", bodyW, h, false,
                ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar();
        ImGui.popStyleColor();
        inspector.render(lastTarget, fullWidth(availWidth) - gripW, h);
        ImGui.endChild();
    }

    /**
     * Draws the scripts' UIs that are popped out into windows of their own.
     * The shell calls this every frame after its own window, whatever page or
     * drawer is showing: a popped-out UI does not belong to either.
     */
    public void renderWindows() {
        windows.render();
    }

    /** The drawer's width once fully open, grip included. */
    private float fullWidth(float availWidth) {
        return ui.m().drawerWidth(preferredWidth, availWidth);
    }

    /**
     * The drawer's left edge: drag to resize, double-click for the default width.
     * It sits between the page and the drawer rather than on top of either, so
     * neither's child windows can take the hover from it.
     */
    private void grip(float gripW, float availWidth, float h) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImGui.invisibleButton("##inspector-grip", gripW, h);
        boolean isActive = ImGui.isItemActive();
        boolean isHot = ImGui.isItemHovered() || isActive;
        ImDrawList draw = ImGui.getWindowDrawList();
        if (isHot) {
            ImGui.setMouseCursor(ImGuiMouseCursor.ResizeEW);
            draw.addRectFilled(x, y, x + gripW, y + h, ImGuiTheme.COL_INFO_SOFT);
        }
        float lineX = x + gripW - 0.5f;
        draw.addLine(lineX, y, lineX, y + h, ImGuiTheme.COL_BORDER, ui.m().hairline());
        if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
            preferredWidth = 0f;
        } else if (isActive) {
            // Floored above 0: a fling past the page must not read as "no preference" and snap back open.
            float dragged = Math.max(ui.m().hairline(), fullWidth(availWidth) - ImGui.getIO().getMouseDeltaX());
            preferredWidth = ui.m().drawerWidth(dragged, availWidth);
        }
    }

    /**
     * Esc closes the inspector, unless a popup (the script picker) is up or a
     * field is being edited. This runs before the page draws: the picker handles
     * its own Esc and closes during the page's render, so checking afterwards
     * would let the same key press close the inspector too.
     */
    private void handleEscape(PageId shown) {
        if (!state.docksBeside(shown) || ImGui.isAnyItemActive() || ImGui.isPopupOpen("", POPUP_ANY)) {
            return;
        }
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            state.close();
        }
    }
}
