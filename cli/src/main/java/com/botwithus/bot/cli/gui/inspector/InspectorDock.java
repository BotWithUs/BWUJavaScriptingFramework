package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.nav.PageId;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiPopupFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.Optional;

/**
 * The drawer the shell docks on the right of the page body, in both modes. It
 * slides open beside the page that owns the open script and pushes that page
 * aside rather than covering it. Render thread only.
 *
 * <p>Each frame the shell calls {@link #beginFrame} before drawing the page, to
 * learn how much width to leave, and {@link #render} after it.</p>
 */
public final class InspectorDock {

    private static final int POPUP_ANY = ImGuiPopupFlags.AnyPopupId | ImGuiPopupFlags.AnyPopupLevel;

    private final Controls ui;
    private final InspectorState state;
    private final InspectorSource source;
    private final ConfigInspector inspector;
    private float progress;
    private InspectorTarget lastTarget;

    public InspectorDock(Controls ui, InspectorState state, InspectorSource source) {
        this.ui = ui;
        this.state = state;
        this.source = source;
        this.inspector = new ConfigInspector(ui, state);
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
        float width = ui.motion().ease(progress) * ui.m().drawerWidth(availWidth);
        return lastTarget != null && width > 1f ? width : 0f;
    }

    /** Draws the drawer at {@code width} (possibly mid-slide) and full height {@code h}. */
    public void render(float width, float availWidth, float h) {
        if (lastTarget == null || width <= 0f) {
            return;
        }
        Controls.pushColor(ImGuiCol.ChildBg, ImGuiTheme.COL_SURFACE);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.beginChild("##inspector-drawer", width, h, false,
                ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar();
        ImGui.popStyleColor();
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getWindowPosX();
        float y = ImGui.getWindowPosY();
        inspector.render(lastTarget, ui.m().drawerWidth(availWidth), h);
        draw.addLine(x + 0.5f, y, x + 0.5f, y + h, ImGuiTheme.COL_BORDER, ui.m().hairline());
        ImGui.endChild();
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
