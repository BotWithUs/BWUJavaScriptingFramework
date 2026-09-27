package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.window.BorderHitTest;
import com.botwithus.bot.cli.gui.window.MoveDrag;
import com.botwithus.bot.cli.gui.window.ResizeDrag;
import com.botwithus.bot.cli.gui.window.ResizeEdge;
import com.botwithus.bot.cli.gui.window.ScreenPoint;
import com.botwithus.bot.cli.gui.window.WindowControls;
import com.botwithus.bot.cli.gui.window.WindowRect;

import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.Optional;

/**
 * The frame the frameless window draws for itself.
 *
 * <ul>
 *   <li><b>Window buttons</b> — minimise, maximise/restore and close, as icon
 *       buttons from the widget kit at the right of the top bar.</li>
 *   <li><b>Drag region</b> — the rest of the top bar moves the window, and a
 *       double-click maximises or restores it. Dragging a maximised window
 *       restores it under the cursor, as Windows does.</li>
 *   <li><b>Resize border</b> — a band {@link BorderHitTest#border()} deep along
 *       each edge, with wider corners, shows the matching resize cursor and
 *       resizes from that edge. Hidden while maximised.</li>
 * </ul>
 *
 * <p>The border is four thin ImGui windows over the edges, drawn after the
 * shell so they sit above it: the page's own widgets near an edge then cannot
 * take a press that starts a resize. The geometry is worked out by
 * {@link BorderHitTest}, {@link ResizeDrag} and {@link MoveDrag}; this class
 * only routes ImGui's mouse state to them and their results to the window.</p>
 */
public final class FramelessChrome implements WindowChrome {

    private static final int EDGE_FLAGS = ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoBackground | ImGuiWindowFlags.NoNav
            | ImGuiWindowFlags.NoFocusOnAppearing | ImGuiWindowFlags.NoBringToFrontOnFocus
            | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse;
    /** Three window buttons. */
    private static final int BUTTON_COUNT = 3;
    /** Resize border depth (about 6 px at the default text size) and corner reach, in spacing units. */
    private static final float BORDER_UNITS = 2f;
    private static final float CORNER_UNITS = 5f;
    /** Style vars pushed around each edge window: padding, minimum size, border. */
    private static final int EDGE_STYLE_VARS = 3;

    /** Where a press on the drag region began. */
    private record Press(ScreenPoint cursor, WindowRect bounds, boolean wasMaximised) {}

    private final Controls ui;
    private final WindowControls window;
    private final BorderHitTest hitTest;
    private final int minWidth;
    private final int minHeight;

    private Press press;
    private MoveDrag move;
    private ResizeDrag resize;

    /**
     * @param minWidth  narrowest the border may resize the window to
     * @param minHeight shortest the border may resize the window to
     */
    public FramelessChrome(Controls ui, WindowControls window, int minWidth, int minHeight) {
        this.ui = ui;
        this.window = window;
        this.hitTest = new BorderHitTest(ui.m().u(BORDER_UNITS), ui.m().u(CORNER_UNITS));
        this.minWidth = minWidth;
        this.minHeight = minHeight;
    }

    // ── Window buttons ─────────────────────────────────────────────────────

    @Override
    public float controlsWidth() {
        return buttonSize() * BUTTON_COUNT + gap() * (BUTTON_COUNT - 1);
    }

    @Override
    public void renderControls(float right, float y, float height) {
        float size = buttonSize();
        float top = y + (height - size) * 0.5f;
        float x = right - controlsWidth();
        if (button("##win-min", Icons.WINDOW_MINIMIZE, x, top)) {
            window.minimise();
        }
        x += size + gap();
        String maxIcon = window.isMaximised() ? Icons.WINDOW_RESTORE : Icons.WINDOW;
        if (button("##win-max", maxIcon, x, top)) {
            toggleMaximised();
        }
        x += size + gap();
        if (button("##win-close", Icons.XMARK, x, top)) {
            window.close();
        }
    }

    private boolean button(String id, String icon, float x, float y) {
        ImGui.setCursorScreenPos(x, y);
        return ui.button(id, icon, "", Tone.ICON, true, buttonSize());
    }

    private float buttonSize() {
        return ui.buttonWidth(Icons.XMARK, "", Tone.ICON);
    }

    private float gap() {
        return ui.m().u(1);
    }

    private void toggleMaximised() {
        if (window.isMaximised()) {
            window.restore();
        } else {
            window.maximise();
        }
    }

    // ── Drag region ────────────────────────────────────────────────────────

    @Override
    public void renderDragRegion(float x, float y, float width, float height) {
        ImGui.setCursorScreenPos(x, y);
        ImGui.invisibleButton("##window-drag", width, height);
        if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
            press = null;
            move = null;
            toggleMaximised();
            return;
        }
        if (ImGui.isItemActivated()) {
            press = new Press(window.cursor(), window.bounds(), window.isMaximised());
            move = null;
        }
        if (ImGui.isItemActive() && press != null && ImGui.isMouseDragging(ImGuiMouseButton.Left)) {
            followCursor();
        }
        if (ImGui.isItemDeactivated()) {
            press = null;
            move = null;
        }
    }

    private void followCursor() {
        if (move == null) {
            if (press.wasMaximised()) {
                window.restore();
                move = MoveDrag.grabRestoring(press.bounds(), window.bounds(), press.cursor());
            } else {
                move = MoveDrag.grab(press.bounds(), press.cursor());
            }
        }
        window.setBounds(move.positionAt(window.bounds(), window.cursor()));
    }

    // ── Resize border ──────────────────────────────────────────────────────

    @Override
    public void renderEdges() {
        if (window.isMaximised()) {
            resize = null;
            return;
        }
        ImGuiViewport vp = ImGui.getMainViewport();
        float x = vp.getPosX();
        float y = vp.getPosY();
        float w = vp.getSizeX();
        float h = vp.getSizeY();
        float b = hitTest.border();
        ImGui.getForegroundDrawList().addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f,
                ImGuiTheme.COL_BORDER, 0f, 0, ui.m().hairline());
        edge("##edge-top", x, y, w, b);
        edge("##edge-bottom", x, y + h - b, w, b);
        edge("##edge-left", x, y + b, b, h - b * 2f);
        edge("##edge-right", x + w - b, y + b, b, h - b * 2f);
        if (resize != null) {
            showCursor(resize.edge());
        }
    }

    /** One strip of the border, as a window of its own above the shell. */
    private void edge(String id, float x, float y, float w, float h) {
        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(w, h);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowMinSize, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f);
        ImGui.begin(id, EDGE_FLAGS);
        ImGui.popStyleVar(EDGE_STYLE_VARS);
        ImGui.invisibleButton(id + "-grab", Math.max(1f, w), Math.max(1f, h));
        if (ImGui.isItemActivated()) {
            resize = edgeUnderMouse()
                    .map(edge -> new ResizeDrag(window.bounds(), edge, window.cursor()))
                    .orElse(null);
        }
        if (ImGui.isItemActive() && resize != null) {
            window.setBounds(resize.rectAt(window.cursor(), minWidth, minHeight));
        } else if (ImGui.isItemHovered()) {
            edgeUnderMouse().ifPresent(FramelessChrome::showCursor);
        }
        if (ImGui.isItemDeactivated()) {
            resize = null;
        }
        ImGui.end();
    }

    private Optional<ResizeEdge> edgeUnderMouse() {
        ImGuiViewport vp = ImGui.getMainViewport();
        return hitTest.edgeAt(ImGui.getMousePosX() - vp.getPosX(), ImGui.getMousePosY() - vp.getPosY(),
                vp.getSizeX(), vp.getSizeY());
    }

    private static void showCursor(ResizeEdge edge) {
        ImGui.setMouseCursor(switch (edge) {
            case NORTH, SOUTH -> ImGuiMouseCursor.ResizeNS;
            case WEST, EAST -> ImGuiMouseCursor.ResizeEW;
            case NORTH_WEST, SOUTH_EAST -> ImGuiMouseCursor.ResizeNWSE;
            case NORTH_EAST, SOUTH_WEST -> ImGuiMouseCursor.ResizeNESW;
        });
    }
}
