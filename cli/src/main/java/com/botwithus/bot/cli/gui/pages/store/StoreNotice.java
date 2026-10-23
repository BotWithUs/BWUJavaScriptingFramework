package com.botwithus.bot.cli.gui.pages.store;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.cli.gui.Icons;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;

/**
 * The page body when there is no catalogue to list: a centred mark, what is
 * wrong, what to do about it, Try again, and a reminder that installed scripts
 * still work. The words are the host's existing ones for each launcher answer.
 */
final class StoreNotice {

    private static final float MARK_EM = 3.733f;
    private static final float TEXT_EM = 26.667f;
    private static final float LINE_HEIGHT = 1.45f;
    private static final String TRY_AGAIN = "Try again";
    private static final String STILL_WORK = "Your installed scripts still work. They live in";
    private static final String INSTALLED = "Installed scripts";

    private record Copy(String icon, String headline, String body) {}

    private final StoreWidgets w;
    private final Controls ui;

    StoreNotice(StoreWidgets widgets) {
        this.w = widgets;
        this.ui = widgets.ui();
    }

    private static Copy copyFor(StoreStatus.Notice notice) {
        return switch (notice.reason()) {
            case LAUNCHER_NOT_RUNNING -> new Copy(Icons.PLUG, "Start the launcher to see your scripts",
                    "The BotWithUs launcher fetches your subscriptions for you. Sign in there, then refresh.");
            case SIGNED_OUT -> new Copy(Icons.USERS, "Sign in to see your scripts",
                    "The launcher is running but no account is signed in. Sign in there, then refresh.");
            case NO_SUBSCRIPTION -> new Copy(Icons.CROWN, "This account needs a BotWithUs subscription",
                    "Subscribing to individual scripts is not enough to browse the store. "
                            + "Add a BotWithUs subscription, then refresh.");
            case FAILED -> new Copy(Icons.WARNING, "We could not load your scripts", notice.detail());
        };
    }

    /** Draws the notice centred in the current window, from {@code top} down. */
    void render(StoreStatus.Notice notice, StoreActions actions, float top) {
        ImGuiTheme.Metrics m = ui.m();
        Copy copy = copyFor(notice);
        ImFont body = ui.fonts().small();
        float textW = Math.min(ui.fonts().body().getFontSize() * TEXT_EM, ImGui.getWindowWidth() - m.u(6) * 2f);
        int lines = ui.wrap(body, copy.body(), textW).size();
        float mark = ui.fonts().body().getFontSize() * MARK_EM;
        float blockH = mark + m.u(4) + ui.fonts().bodyMedium().getFontSize() * LINE_HEIGHT + m.u(3)
                + lines * body.getFontSize() * LINE_HEIGHT + m.u(3) + m.controlHeight() + m.u(3)
                + ui.fonts().caption().getFontSize() * LINE_HEIGHT;
        float bottom = ImGui.getWindowPosY() + ImGui.getWindowHeight();
        float y = Math.max(top + m.u(6), top + (bottom - top - blockH) * 0.5f);
        float cx = ImGui.getWindowPosX() + ImGui.getWindowWidth() * 0.5f;
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addCircle(cx, y + mark * 0.5f, mark * 0.5f, ImGuiTheme.COL_BORDER, 0, m.hairline());
        ImFont iconFont = ui.fonts().titleMedium();
        ui.text(draw, iconFont, cx - ui.width(iconFont, copy.icon()) * 0.5f,
                y + (mark - iconFont.getFontSize()) * 0.5f, ImGuiTheme.COL_FG2, copy.icon());
        y += mark + m.u(4);
        ImFont head = ui.fonts().bodyMedium();
        ui.text(draw, head, cx - ui.width(head, copy.headline()) * 0.5f, y, ImGuiTheme.COL_FG, copy.headline());
        y += head.getFontSize() * LINE_HEIGHT + m.u(3);
        y += ui.centredParagraph(draw, body, cx, y, textW, body.getFontSize() * LINE_HEIGHT, ImGuiTheme.COL_FG2,
                copy.body()) + m.u(3);
        tryAgain(actions, cx, y);
        stillWork(draw, actions, cx, y + m.controlHeight() + m.u(3));
    }

    private void tryAgain(StoreActions actions, float cx, float y) {
        float bw = ui.buttonWidth(Icons.ROTATE, TRY_AGAIN, Tone.PRIMARY);
        ImGui.setCursorScreenPos(cx - bw * 0.5f, y);
        if (ui.button("##store-try-again", Icons.ROTATE, TRY_AGAIN, Tone.PRIMARY, true)) {
            actions.refresh();
        }
    }

    /** "Your installed scripts still work. They live in Installed scripts." with the last two words a link. */
    private void stillWork(ImDrawList draw, StoreActions actions, float cx, float y) {
        ImFont font = ui.fonts().caption();
        float lead = ui.width(font, STILL_WORK + " ");
        float total = lead + ui.width(font, INSTALLED + ".");
        float x = cx - total * 0.5f;
        ui.text(draw, font, x, y, ImGuiTheme.COL_FG2, STILL_WORK + " ");
        ImGui.setCursorScreenPos(x + lead, y);
        if (w.inlineLink("##store-open-installed", INSTALLED, font)) {
            actions.openInstalledScripts();
        }
        ui.text(draw, font, x + lead + ui.width(font, INSTALLED), y, ImGuiTheme.COL_FG2, ".");
    }
}
