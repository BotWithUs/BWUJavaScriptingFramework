package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.gui.nav.PageRegistry;
import com.botwithus.bot.cli.gui.nav.Sidebar;
import com.botwithus.bot.cli.gui.notify.Notification;
import com.botwithus.bot.cli.gui.notify.NotificationOverlay;
import com.botwithus.bot.cli.gui.usermode.board.ClientBoard;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * One shell, two depths: the top bar, the mode's body and the status bar, laid
 * out edge to edge in a full-window ImGui window, with toasts floating above.
 * Normal mode's body is the Clients page; Advanced adds the sidebar and shows
 * whichever page it has selected, Clients included. The real app and the
 * dev-only preview both draw through this class, so what the preview captures
 * is what users get.
 */
public final class Shell {

    private final Controls ui;
    private final TopBar topBar;
    private final StatusBar statusBar;
    private final Sidebar sidebar;
    private final PageRegistry pages;
    private final NotificationOverlay toasts;

    public Shell(Controls ui, PageRegistry pages, NotificationOverlay toasts) {
        this.ui = ui;
        this.topBar = new TopBar(ui);
        this.statusBar = new StatusBar(ui);
        this.sidebar = new Sidebar(ui);
        this.pages = pages;
        this.toasts = toasts;
    }

    /**
     * Draws one frame of the shell.
     *
     * @param board   what the status bar reports
     * @param onToast runs a toast's action button
     * @return the mode for the next frame (F12 or the switch may have changed it)
     */
    public AppMode render(AppMode mode, ClientBoard board, Consumer<Notification> onToast) {
        AppMode next = mode;
        if (ImGui.isKeyPressed(ImGuiKey.F12, false)) {
            next = mode == AppMode.NORMAL ? AppMode.ADVANCED : AppMode.NORMAL;
        }
        var vp = ImGui.getMainViewport();
        ImGui.setNextWindowPos(vp.getPosX(), vp.getPosY(), ImGuiCond.Always);
        ImGui.setNextWindowSize(vp.getSizeX(), vp.getSizeY(), ImGuiCond.Always);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.begin("##main", ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoBringToFrontOnFocus
                | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar();

        AppMode clicked = topBar.render(next);
        if (clicked != null) {
            next = clicked;
        }
        float top = topBar.height();
        float bodyH = ImGui.getWindowHeight() - top - statusBar.height();
        ImGui.setCursorPos(0f, top);
        renderBody(next, bodyH);
        ImGui.setCursorPos(0f, top + bodyH);
        statusBar.render(board, next);
        ImGui.end();

        toasts.render(ui, vp.getPosY() + top + ui.m().u(3), onToast);
        return next;
    }

    private void renderBody(AppMode mode, float h) {
        beginBareChild("##" + mode.name().toLowerCase(Locale.ROOT) + "-body", 0f, h);
        if (mode == AppMode.ADVANCED) {
            sidebar.render(pages, h);
            ImGui.sameLine(0f, 0f);
            beginBareChild("##page", 0f, h);
            pages.bodyFor(mode).render();
            ImGui.endChild();
        } else {
            pages.bodyFor(mode).render();
        }
        ImGui.endChild();
    }

    /** A child with no padding and no scrolling of its own; the page inside decides both. */
    private static void beginBareChild(String id, float w, float h) {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.beginChild(id, w, h, false, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        ImGui.popStyleVar();
    }
}
