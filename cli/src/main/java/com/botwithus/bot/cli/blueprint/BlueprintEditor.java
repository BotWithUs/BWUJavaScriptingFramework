package com.botwithus.bot.cli.blueprint;

import com.botwithus.bot.api.blueprint.BlueprintGraph;
import com.botwithus.bot.api.blueprint.BlueprintMetadata;
import com.botwithus.bot.api.blueprint.Link;
import com.botwithus.bot.api.blueprint.NodeDefinition;
import com.botwithus.bot.api.blueprint.NodeInstance;
import com.botwithus.bot.api.blueprint.PinDefinition;
import com.botwithus.bot.api.blueprint.PinDirection;
import com.botwithus.bot.api.blueprint.PinType;
import com.botwithus.bot.core.blueprint.registry.DataNodes;
import com.botwithus.bot.core.blueprint.registry.DebugNodes;
import com.botwithus.bot.core.blueprint.registry.FlowControlNodes;
import com.botwithus.bot.core.blueprint.registry.GameApiNodes;
import com.botwithus.bot.core.blueprint.registry.LogicNodes;
import com.botwithus.bot.core.blueprint.registry.NodeRegistry;
import com.botwithus.bot.core.blueprint.serialization.BlueprintSerializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiWindowFlags;

import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Main blueprint editor orchestrator. Uses pure ImGui drawing (no imgui-node-editor extension)
 * to render nodes, pins, and links on a scrollable canvas.
 * <p>
 * Called from {@code ImGuiApp} when in editor mode (toggled with F2).
 */
public class BlueprintEditor {

    private static final Logger log = LoggerFactory.getLogger(BlueprintEditor.class);

    private static final int COL_CANVAS_BG   = NodeRenderer.packColor(30, 30, 32, 255);
    private static final int COL_GRID_LINE   = NodeRenderer.packColor(50, 50, 55, 255);
    private static final float GRID_SIZE     = 32f;

    // Side panel widths as fraction of available space
    private static final float PALETTE_FRACTION = 0.16f;    // ~16% of width
    private static final float PROPERTIES_FRACTION = 0.18f;  // ~18% of width

    private BlueprintGraph graph;
    private final NodeRegistry registry;
    private final BlueprintEditorState state;

    private final NodeRenderer nodeRenderer;
    private final LinkRenderer linkRenderer;
    private final NodePalette palette;
    private final PropertyPanel propertyPanel;
    private final ContextMenuHandler contextMenu;
    private final BlueprintMenuBar menuBar;
    private final FileDialog fileDialog;

    public BlueprintEditor() {
        registry = new NodeRegistry();
        FlowControlNodes.registerAll(registry);
        DataNodes.registerAll(registry);
        LogicNodes.registerAll(registry);
        DebugNodes.registerAll(registry);
        GameApiNodes.registerAll(registry);

        graph = new BlueprintGraph(new BlueprintMetadata("Untitled", "1.0", "", ""));
        state = new BlueprintEditorState();

        nodeRenderer = new NodeRenderer();
        linkRenderer = new LinkRenderer(nodeRenderer);
        palette = new NodePalette();
        propertyPanel = new PropertyPanel();
        contextMenu = new ContextMenuHandler();
        menuBar = new BlueprintMenuBar();
        fileDialog = new FileDialog(Path.of("scripts/blueprints"));
    }

    /**
     * Renders the full blueprint editor UI.
     */
    public void render() {
        // Menu bar
        BlueprintMenuBar.Action action = menuBar.render(state, graph);
        handleMenuAction(action);

        // Three-column layout: Palette | Canvas | Properties
        float availableWidth = ImGui.getContentRegionAvailX();
        float paletteWidth = Math.max(150f, availableWidth * PALETTE_FRACTION);
        float propertiesWidth = Math.max(150f, availableWidth * PROPERTIES_FRACTION);
        float canvasWidth = availableWidth - paletteWidth - propertiesWidth;
        if (canvasWidth < 100f) {
            // If window is too narrow, give canvas priority
            paletteWidth = 0;
            propertiesWidth = 0;
            canvasWidth = availableWidth;
        }

        float availableHeight = ImGui.getContentRegionAvailY();

        // Left panel: Node Palette
        if (paletteWidth > 0) {
            palette.render(registry, graph, state, paletteWidth, availableHeight);
            ImGui.sameLine();
        }

        // Center: Node Canvas
        ImGui.beginChild("canvas_region", canvasWidth, availableHeight, false,
                ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoMove);
        renderCanvas();
        ImGui.endChild();

        // Right panel: Property Panel
        if (propertiesWidth > 0) {
            ImGui.sameLine();
            propertyPanel.render(graph, registry, state, propertiesWidth, availableHeight);
        }

        // File dialogs
        renderFileDialogs();
    }

    /**
     * Renders the node editor canvas with all nodes, links, and interaction handling.
     */
    private void renderCanvas() {
        ImDrawList drawList = ImGui.getWindowDrawList();

        // Canvas region bounds on screen
        ImVec2 canvasPos = ImGui.getCursorScreenPos();
        ImVec2 canvasSize = ImGui.getContentRegionAvail();
        float canvasX = canvasPos.x;
        float canvasY = canvasPos.y;
        float canvasW = canvasSize.x;
        float canvasH = canvasSize.y;
        float zoom = state.getCanvasZoom();

        // Background
        drawList.addRectFilled(canvasX, canvasY, canvasX + canvasW, canvasY + canvasH, COL_CANVAS_BG);

        // Grid (zoomed)
        drawGrid(drawList, canvasX, canvasY, canvasW, canvasH, zoom);

        // Clip drawing to canvas area
        drawList.pushClipRect(canvasX, canvasY, canvasX + canvasW, canvasY + canvasH, true);

        // Render links (behind nodes)
        linkRenderer.renderAll(drawList, graph, registry, state, canvasX, canvasY, zoom);

        // Render nodes
        for (NodeInstance node : graph.getNodes()) {
            NodeDefinition def = registry.getDefinition(node.getTypeId());
            if (def != null) {
                float nodeW = nodeRenderer.calcNodeWidth(def) * zoom;
                float nodeH = nodeRenderer.calcNodeHeight(def) * zoom;
                float screenX = node.getX() * zoom + state.getCanvasOffsetX() + canvasX;
                float screenY = node.getY() * zoom + state.getCanvasOffsetY() + canvasY;
                nodeRenderer.render(drawList, node, def, state, screenX, screenY, nodeW, nodeH, zoom);
            }
        }

        drawList.popClipRect();

        // Invisible button to capture mouse events over the entire canvas
        ImGui.setCursorScreenPos(canvasX, canvasY);
        ImGui.invisibleButton("canvas_input", canvasW, canvasH);
        boolean canvasHovered = ImGui.isItemHovered();

        // Handle all mouse interactions
        if (canvasHovered) {
            handleMouseInteractions(canvasX, canvasY);
        }

        // Handle keyboard shortcuts
        handleKeyboard();

        // Zoom indicator
        if (zoom != 1.0f) {
            String zoomLabel = String.format("%.0f%%", zoom * 100);
            float textW = ImGui.calcTextSize(zoomLabel).x;
            drawList.addText(canvasX + canvasW - textW - 8, canvasY + 4,
                    NodeRenderer.packColor(180, 180, 180, 200), zoomLabel);
        }

        // Render context menus (these are ImGui popups, rendered outside clip)
        contextMenu.render(graph, registry, state);
    }

    private void drawGrid(ImDrawList drawList, float canvasX, float canvasY,
                           float canvasW, float canvasH, float zoom) {
        float gridStep = GRID_SIZE * zoom;
        if (gridStep < 4f) return; // don't draw grid when zoomed out too far

        float offsetX = state.getCanvasOffsetX() % gridStep;
        float offsetY = state.getCanvasOffsetY() % gridStep;

        for (float x = offsetX; x < canvasW; x += gridStep) {
            drawList.addLine(canvasX + x, canvasY, canvasX + x, canvasY + canvasH, COL_GRID_LINE);
        }
        for (float y = offsetY; y < canvasH; y += gridStep) {
            drawList.addLine(canvasX, canvasY + y, canvasX + canvasW, canvasY + y, COL_GRID_LINE);
        }
    }

    /**
     * Converts screen coordinates to graph-space coordinates.
     */
    private float screenToGraphX(float screenX, float canvasX) {
        return (screenX - canvasX - state.getCanvasOffsetX()) / state.getCanvasZoom();
    }

    private float screenToGraphY(float screenY, float canvasY) {
        return (screenY - canvasY - state.getCanvasOffsetY()) / state.getCanvasZoom();
    }

    /**
     * Converts graph-space coordinates to screen coordinates.
     */
    private float graphToScreenX(float graphX, float canvasX) {
        return graphX * state.getCanvasZoom() + state.getCanvasOffsetX() + canvasX;
    }

    private float graphToScreenY(float graphY, float canvasY) {
        return graphY * state.getCanvasZoom() + state.getCanvasOffsetY() + canvasY;
    }

    /**
     * Handles all mouse-based interactions on the canvas: node selection, dragging,
     * link creation, panning, zooming, and context menus.
     */
    private void handleMouseInteractions(float canvasX, float canvasY) {
        float mouseX = ImGui.getMousePosX();
        float mouseY = ImGui.getMousePosY();
        float zoom = state.getCanvasZoom();

        // Scroll wheel zoom
        float scrollY = ImGui.getIO().getMouseWheel();
        if (scrollY != 0) {
            float zoomDelta = scrollY * 0.1f * zoom;
            state.zoomCanvas(zoomDelta, mouseX, mouseY, canvasX, canvasY);
        }

        // Update hovered pin
        updateHoveredPin(mouseX, mouseY, canvasX, canvasY);

        // Left mouse button press
        if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            handleLeftClick(mouseX, mouseY, canvasX, canvasY);
        }

        // Left mouse button drag
        if (ImGui.isMouseDragging(ImGuiMouseButton.Left, 2f)) {
            handleLeftDrag(mouseX, mouseY, canvasX, canvasY);
        }

        // Left mouse button release
        if (ImGui.isMouseReleased(ImGuiMouseButton.Left)) {
            handleLeftRelease(mouseX, mouseY, canvasX, canvasY);
        }

        // Middle mouse button drag for panning
        if (ImGui.isMouseDragging(ImGuiMouseButton.Middle, 0f)) {
            float dx = ImGui.getIO().getMouseDeltaX();
            float dy = ImGui.getIO().getMouseDeltaY();
            state.panCanvas(dx, dy);
        }

        // Right click for context menu
        if (ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            handleRightClick(mouseX, mouseY, canvasX, canvasY);
        }
    }

    /**
     * Computes the zoomed screen position and size for a node.
     */
    private float[] nodeScreenRect(NodeInstance node, NodeDefinition def, float canvasX, float canvasY) {
        float zoom = state.getCanvasZoom();
        float nodeW = nodeRenderer.calcNodeWidth(def) * zoom;
        float nodeH = nodeRenderer.calcNodeHeight(def) * zoom;
        float screenX = node.getX() * zoom + state.getCanvasOffsetX() + canvasX;
        float screenY = node.getY() * zoom + state.getCanvasOffsetY() + canvasY;
        return new float[]{screenX, screenY, nodeW, nodeH};
    }

    /**
     * Computes zoomed screen position for a pin.
     */
    private float[] pinScreenPos(NodeInstance node, NodeDefinition def, int pinIndex,
                                  float canvasX, float canvasY) {
        float zoom = state.getCanvasZoom();
        float nodeW = nodeRenderer.calcNodeWidth(def) * zoom;
        float screenX = node.getX() * zoom + state.getCanvasOffsetX() + canvasX;
        float screenY = node.getY() * zoom + state.getCanvasOffsetY() + canvasY;

        List<PinDefinition> pins = def.pins();
        PinDefinition pin = pins.get(pinIndex);
        int idx = 0;
        for (int i = 0; i < pinIndex; i++) {
            if (pins.get(i).direction() == pin.direction()) idx++;
        }

        if (pin.direction() == PinDirection.INPUT) {
            return nodeRenderer.getInputPinScreenPos(screenX, screenY, nodeW, idx, zoom);
        } else {
            return nodeRenderer.getOutputPinScreenPos(screenX, screenY, nodeW, idx, zoom);
        }
    }

    private void updateHoveredPin(float mouseX, float mouseY, float canvasX, float canvasY) {
        state.setHoveredPinId(-1);
        state.setHoveredNodeId(-1);

        for (NodeInstance node : graph.getNodes()) {
            NodeDefinition def = registry.getDefinition(node.getTypeId());
            if (def == null) continue;

            float[] rect = nodeScreenRect(node, def, canvasX, canvasY);
            if (nodeRenderer.isPointOnNode(mouseX, mouseY, rect[0], rect[1], rect[2], rect[3])) {
                state.setHoveredNodeId(node.getId());
            }

            List<PinDefinition> pins = def.pins();
            for (int i = 0; i < pins.size(); i++) {
                float[] pos = pinScreenPos(node, def, i, canvasX, canvasY);
                if (nodeRenderer.isPointOnPin(mouseX, mouseY, pos[0], pos[1])) {
                    state.setHoveredPinId(node.pinId(i));
                }
            }
        }
    }

    private void handleLeftClick(float mouseX, float mouseY, float canvasX, float canvasY) {
        // Check if clicking on a pin (start link drag)
        for (NodeInstance node : graph.getNodes()) {
            NodeDefinition def = registry.getDefinition(node.getTypeId());
            if (def == null) continue;

            List<PinDefinition> pins = def.pins();
            for (int i = 0; i < pins.size(); i++) {
                float[] pos = pinScreenPos(node, def, i, canvasX, canvasY);
                if (nodeRenderer.isPointOnPin(mouseX, mouseY, pos[0], pos[1])) {
                    boolean isOutput = pins.get(i).direction() == PinDirection.OUTPUT;
                    state.startLinkDrag(node.pinId(i), node.getId(), isOutput, mouseX, mouseY);
                    return;
                }
            }
        }

        // Check if clicking on a node title bar (start drag) or body (select)
        List<NodeInstance> nodes = graph.getNodes();
        for (int n = nodes.size() - 1; n >= 0; n--) {
            NodeInstance node = nodes.get(n);
            NodeDefinition def = registry.getDefinition(node.getTypeId());
            if (def == null) continue;

            float[] rect = nodeScreenRect(node, def, canvasX, canvasY);
            if (nodeRenderer.isPointOnNode(mouseX, mouseY, rect[0], rect[1], rect[2], rect[3])) {
                if (!ImGui.getIO().getKeyCtrl()) {
                    state.clearSelection();
                }
                state.selectNode(node.getId());

                if (nodeRenderer.isPointOnTitleBar(mouseX, mouseY, rect[0], rect[1], rect[2],
                        state.getCanvasZoom())) {
                    state.setDraggingNodeId(node.getId());
                    state.setDragOffset(mouseX - rect[0], mouseY - rect[1]);
                }
                return;
            }
        }

        // Clicked on empty canvas — deselect all
        state.clearSelection();
    }

    private void handleLeftDrag(float mouseX, float mouseY, float canvasX, float canvasY) {
        float zoom = state.getCanvasZoom();

        // Node dragging
        if (state.getDraggingNodeId() >= 0) {
            NodeInstance node = graph.findNode(state.getDraggingNodeId());
            if (node != null) {
                float newX = (mouseX - state.getDragOffsetX() - state.getCanvasOffsetX() - canvasX) / zoom;
                float newY = (mouseY - state.getDragOffsetY() - state.getCanvasOffsetY() - canvasY) / zoom;
                node.setX(newX);
                node.setY(newY);
                state.markDirty();
            }
        }

        // Link dragging
        if (state.isDraggingLink()) {
            state.updateLinkDragEnd(mouseX, mouseY);
        }
    }

    private void handleLeftRelease(float mouseX, float mouseY, float canvasX, float canvasY) {
        // Finish node dragging
        state.setDraggingNodeId(-1);

        // Finish link dragging — check if released on a compatible pin
        if (state.isDraggingLink()) {
            long sourcePinId = state.getDragSourcePinId();
            boolean sourceIsOutput = state.isDragSourceOutput();

            for (NodeInstance node : graph.getNodes()) {
                NodeDefinition def = registry.getDefinition(node.getTypeId());
                if (def == null) continue;

                List<PinDefinition> pins = def.pins();
                for (int i = 0; i < pins.size(); i++) {
                    float[] pos = pinScreenPos(node, def, i, canvasX, canvasY);
                    if (nodeRenderer.isPointOnPin(mouseX, mouseY, pos[0], pos[1])) {
                        long targetPinId = node.pinId(i);
                        boolean isOutput = pins.get(i).direction() == PinDirection.OUTPUT;

                        if (sourceIsOutput != isOutput) {
                            long outPin = sourceIsOutput ? sourcePinId : targetPinId;
                            long inPin = sourceIsOutput ? targetPinId : sourcePinId;
                            if (validateLink(outPin, inPin)) {
                                graph.addLink(outPin, inPin);
                                state.markDirty();
                            }
                        }
                        break;
                    }
                }
            }

            state.endLinkDrag();
        }
    }

    private void handleRightClick(float mouseX, float mouseY, float canvasX, float canvasY) {
        List<NodeInstance> nodes = graph.getNodes();
        for (int n = nodes.size() - 1; n >= 0; n--) {
            NodeInstance node = nodes.get(n);
            NodeDefinition def = registry.getDefinition(node.getTypeId());
            if (def == null) continue;

            float[] rect = nodeScreenRect(node, def, canvasX, canvasY);
            if (nodeRenderer.isPointOnNode(mouseX, mouseY, rect[0], rect[1], rect[2], rect[3])) {
                contextMenu.openNodeMenu(node.getId());
                return;
            }
        }

        // Right-click on canvas — open add-node menu at graph position
        float graphX = screenToGraphX(mouseX, canvasX);
        float graphY = screenToGraphY(mouseY, canvasY);
        contextMenu.openCanvasMenu(graphX, graphY);
    }

    private void handleKeyboard() {
        // Delete key to remove selected nodes
        if (ImGui.isKeyPressed(GLFW.GLFW_KEY_DELETE)) {
            for (long nodeId : state.getSelectedNodeIds()) {
                graph.removeNode(nodeId);
            }
            if (!state.getSelectedNodeIds().isEmpty()) {
                state.markDirty();
            }
            state.clearSelection();
        }
    }

    /**
     * Validates whether a link between an output pin and an input pin is allowed.
     */
    private boolean validateLink(long outputPinId, long inputPinId) {
        if (outputPinId == inputPinId) return false;

        long nodeA = outputPinId / 1000;
        long nodeB = inputPinId / 1000;
        if (nodeA == nodeB) return false;

        PinType typeA = getPinType(outputPinId);
        PinType typeB = getPinType(inputPinId);
        if (typeA == null || typeB == null) return false;

        return typeA.isCompatibleWith(typeB);
    }

    private PinType getPinType(long pinId) {
        PinDefinition pin = findPinDefinition(pinId);
        return pin != null ? pin.type() : null;
    }

    private PinDefinition findPinDefinition(long pinId) {
        long nodeId = pinId / 1000;
        int pinIndex = (int) (pinId % 1000);

        NodeInstance node = graph.findNode(nodeId);
        if (node == null) return null;

        NodeDefinition def = registry.getDefinition(node.getTypeId());
        if (def == null) return null;

        List<PinDefinition> pins = def.pins();
        if (pinIndex < 0 || pinIndex >= pins.size()) return null;

        return pins.get(pinIndex);
    }

    /**
     * Renders file Open/Save dialogs when requested.
     */
    private void renderFileDialogs() {
        if (state.isShowOpenDialog()) {
            fileDialog.setMode(FileDialog.Mode.OPEN);
            state.setShowOpenDialog(false);
        }
        if (state.isShowSaveDialog()) {
            fileDialog.setMode(FileDialog.Mode.SAVE);
            state.setShowSaveDialog(false);
        }

        FileDialog.Result result = fileDialog.render();
        if (result != null) {
            if (fileDialog.getLastMode() == FileDialog.Mode.OPEN) {
                loadGraph(result.path());
            } else {
                saveGraph(result.path());
            }
        }
    }

    /**
     * Processes actions triggered by the menu bar.
     */
    private void handleMenuAction(BlueprintMenuBar.Action action) {
        switch (action) {
            case NEW -> {
                graph = new BlueprintGraph(new BlueprintMetadata("Untitled", "1.0", "", ""));
                state.clearSelection();
                state.setCurrentFilePath(null);
                state.clearDirty();
            }
            case OPEN -> {
                state.setShowOpenDialog(true);
            }
            case SAVE -> {
                if (state.getCurrentFilePath() != null) {
                    saveGraph(state.getCurrentFilePath());
                } else {
                    state.setShowSaveDialog(true);
                }
            }
            case SAVE_AS -> {
                state.setShowSaveDialog(true);
            }
            case DELETE_SELECTED -> {
                for (long nodeId : state.getSelectedNodeIds()) {
                    graph.removeNode(nodeId);
                }
                state.clearSelection();
                state.markDirty();
            }
            case SELECT_ALL -> {
                for (NodeInstance node : graph.getNodes()) {
                    state.selectNode(node.getId());
                }
            }
            case CLOSE, PLAY, STOP, NONE -> {
                // CLOSE is handled by ImGuiApp (toggle editorMode)
                // PLAY/STOP need CliContext integration (future)
            }
        }
    }

    private void saveGraph(Path path) {
        try {
            BlueprintSerializer.saveToFile(graph, path);
            state.setCurrentFilePath(path);
            state.clearDirty();
        } catch (IOException e) {
            log.error("Failed to save blueprint", e);
        }
    }

    private void loadGraph(Path path) {
        try {
            graph = BlueprintSerializer.loadFromFile(path);
            state.setCurrentFilePath(path);
            state.clearSelection();
            state.clearDirty();
        } catch (IOException e) {
            log.error("Failed to load blueprint", e);
        }
    }

    /**
     * Returns the current graph (for external access, e.g., running the blueprint).
     */
    public BlueprintGraph getGraph() {
        return graph;
    }

    /**
     * Returns the node registry (for external access).
     */
    public NodeRegistry getRegistry() {
        return registry;
    }

    /**
     * Returns whether the CLOSE action was triggered this frame.
     */
    public boolean isCloseRequested() {
        return false;
    }

    /**
     * Cleans up resources. No native context to destroy with pure ImGui rendering.
     */
    public void dispose() {
        // Nothing to clean up — no native node-editor context
    }
}
