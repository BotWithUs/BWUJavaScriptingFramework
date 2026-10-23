package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.cli.gui.HostIni;

import imgui.ImDrawData;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiBackendFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.internal.ImGuiContext;
import imgui.type.ImInt;

import java.nio.file.Path;

/**
 * A host ImGui context with no window and no renderer: the real imgui-java
 * natives, a built font atlas, and a frame loop that draws one top-level
 * window the way the host's shell does. Enough to run Dear ImGui's own checks,
 * which is what these tests are about; nothing reaches a GPU.
 */
final class HeadlessHost implements AutoCloseable {

    static final float WIDTH = 1200f;
    static final float HEIGHT = 800f;
    private static final float FRAME_SECONDS = 1f / 60f;
    /** Any non-zero id: there is no renderer to bind it. */
    private static final long FONT_TEXTURE = 7L;
    private static final int HOST_FLAGS = ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoBackground
            | ImGuiWindowFlags.NoSavedSettings;
    /** A pop-out keeps its title bar, as a framed pop-out does, but is placed by the test. */
    private static final int POP_OUT_FLAGS = ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoCollapse;
    /** The vertical item spacing the drawer pushes around the script-UI child (ConfigInspector). */
    static final float DRAWER_ITEM_SPACING_Y = 6f;

    private final ImGuiContext context;

    private HeadlessHost(ImGuiContext context) {
        this.context = context;
    }

    static HeadlessHost create() {
        ImGuiContext context = ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        // As the host does; only ever read here, and the file does not exist.
        HostIni.attach(Path.of("build", "headless-host-never-written.ini"));
        io.setDisplaySize(WIDTH, HEIGHT);
        io.setDeltaTime(FRAME_SECONDS);
        io.setBackendFlags(ImGuiBackendFlags.RendererHasVtxOffset);
        io.getFonts().addFontDefault();
        io.getFonts().getTexDataAsRGBA32(new ImInt(), new ImInt());
        io.getFonts().setTexID(FONT_TEXTURE);
        return new HeadlessHost(context);
    }

    /** One host frame: {@code body} runs inside the host's top-level window. Returns the frame's draw data. */
    ImDrawData frame(Runnable body) {
        return frame(body, () -> { }, () -> { });
    }

    /**
     * One host frame as the shell runs it: {@code body} inside the host's
     * top-level window, then {@code windows} at the top level, where the host
     * opens its other windows (a popped-out script UI is one), then
     * {@code endOfFrame} once everything is drawn and before Dear ImGui ends
     * the frame, where the host calls {@code ScriptUiRenderer.endFrame()}.
     */
    ImDrawData frame(Runnable body, Runnable windows, Runnable endOfFrame) {
        ImGui.newFrame();
        ImGui.setNextWindowPos(0f, 0f);
        ImGui.setNextWindowSize(WIDTH, HEIGHT);
        ImGui.begin("host", HOST_FLAGS);
        body.run();
        ImGui.end();
        windows.run();
        endOfFrame.run();
        ImGui.render();
        return ImGui.getDrawData();
    }

    /**
     * A top-level window of its own at ({@code x}, {@code y}), the way a
     * popped-out script UI is one, with {@code inside} drawn in it.
     */
    static void window(String title, float x, float y, float width, float height, Runnable inside) {
        ImGui.setNextWindowPos(x, y);
        ImGui.setNextWindowSize(width, height);
        ImGui.begin(title, POP_OUT_FLAGS);
        inside.run();
        ImGui.end();
    }

    /**
     * A drawer-style panel: the item spacing push and the script-UI child that
     * {@code ConfigInspector} wraps a script's UI in, at ({@code x}, {@code y}).
     */
    static void panel(String id, float x, float y, float width, float height, Runnable inside) {
        ImGui.setCursorScreenPos(x, y);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, ImGui.getStyle().getItemSpacingX(), DRAWER_ITEM_SPACING_Y);
        ImGui.beginChild(id, width, height, false);
        inside.run();
        ImGui.endChild();
        ImGui.popStyleVar();
    }

    @Override
    public void close() {
        ImGui.destroyContext(context);
    }
}
