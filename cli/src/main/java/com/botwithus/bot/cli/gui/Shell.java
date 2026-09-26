package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.gui.notify.Notification;
import com.botwithus.bot.cli.gui.notify.NotificationOverlay;
import com.botwithus.bot.cli.gui.usermode.UserModeRenderer;
import com.botwithus.bot.cli.gui.usermode.board.ClientBoard;

import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.function.Consumer;

/**
 * One shell, two depths: the top bar, the mode's body and the status bar, laid
 * out edge to edge in a full-window ImGui window, with toasts floating above.
 * The real app and the dev-only preview both draw through this class, so what
 * the preview captures is what users get.
 */
public final class Shell {

    private final Controls ui;
    private final TopBar topBar;
    private final StatusBar statusBar;
    private final UserModeRenderer clients;
    private final NotificationOverlay toasts;

    public Shell(Controls ui, UserModeRenderer clients, NotificationOverlay toasts) {
        this.ui = ui;
        this.topBar = new TopBar(ui);
        this.statusBar = new StatusBar(ui);
        this.clients = clients;
        this.toasts = toasts;
    }

    /**
     * Draws one frame of the shell.
     *
     * @param advancedBody draws Advanced mode's sidebar and panel into the body region
     * @param onToast      runs a toast's action button
     * @return the mode for the next frame (F12 or the switch may have changed it)
     */
    public AppMode render(AppMode mode, ClientBoard board, Runnable advancedBody, Consumer<Notification> onToast) {
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
        renderBody(next, board, advancedBody, bodyH);
        ImGui.setCursorPos(0f, top + bodyH);
        statusBar.render(board, next);
        ImGui.end();

        toasts.render(ui, vp.getPosY() + top + ui.m().u(3), onToast);
        return next;
    }

    private void renderBody(AppMode mode, ClientBoard board, Runnable advancedBody, float h) {
        switch (mode) {
            case NORMAL -> {
                ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
                ImGui.beginChild("##normal-body", 0f, h, false,
                        ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
                ImGui.popStyleVar();
                clients.render(board);
                ImGui.endChild();
            }
            case ADVANCED -> {
                // Advanced panels were laid out inside the main window's padding;
                // keep giving them that padding so they render exactly as before.
                ImGui.beginChild("##advanced-body", 0f, h, ImGuiChildFlags.AlwaysUseWindowPadding, 0);
                advancedBody.run();
                ImGui.endChild();
            }
        }
    }
}
