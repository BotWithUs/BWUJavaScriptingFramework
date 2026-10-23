package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUI;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

/**
 * Script UIs that each leave one Dear ImGui stack unbalanced, in the direction
 * that matters: popping or ending what they never pushed or began, or pushing
 * or beginning what they never pop or end. Every one draws a line of text
 * first, like a real UI would.
 *
 * <p>{@link #isFatalToday} says whether the breaker takes the process down when
 * drawn straight into the host's context, as master does. The two that are not
 * are controls: Dear ImGui does not check a leaked clip rect or text-wrap
 * position, so neither ever crashed the host, and the guard must leave them
 * drawing too.</p>
 */
enum Breaker {

    STYLE_VAR_POP(true, () -> ImGui.popStyleVar()),
    STYLE_VAR_LEAK(true, () -> ImGui.pushStyleVar(ImGuiStyleVar.Alpha, 0.5f)),
    STYLE_COLOUR_POP(true, () -> ImGui.popStyleColor()),
    STYLE_COLOUR_LEAK(true, () -> ImGui.pushStyleColor(ImGuiCol.Text, 0xFF0000FF)),
    ID_POP(true, () -> ImGui.popID()),
    ID_LEAK(true, () -> ImGui.pushID("leaked")),
    CHILD_END(true, () -> ImGui.endChild()),
    CHILD_LEAK(true, () -> ImGui.beginChild("leaked-child")),
    GROUP_END(true, () -> ImGui.endGroup()),
    GROUP_LEAK(true, () -> ImGui.beginGroup()),
    WINDOW_END(true, () -> ImGui.end()),
    WINDOW_LEAK(true, () -> ImGui.begin("leaked-window")),
    DISABLED_END(true, () -> ImGui.endDisabled()),
    DISABLED_LEAK(true, () -> ImGui.beginDisabled()),
    CLIP_RECT_POP(true, () -> ImGui.popClipRect()),
    CLIP_RECT_LEAK(false, () -> ImGui.pushClipRect(0f, 0f, 100f, 100f, true)),
    TEXT_WRAP_POP(true, () -> ImGui.popTextWrapPos()),
    TEXT_WRAP_LEAK(false, () -> ImGui.pushTextWrapPos()),
    TOOLTIP_END(true, () -> ImGui.endTooltip()),
    TOOLTIP_LEAK(true, () -> ImGui.beginTooltip()),
    POPUP_END(true, () -> ImGui.endPopup()),
    POPUP_LEAK(true, Breaker::openAndLeakPopup),
    COMBO_END(true, () -> ImGui.endCombo());

    private final boolean isFatalToday;
    private final Runnable breakage;

    Breaker(boolean isFatalToday, Runnable breakage) {
        this.isFatalToday = isFatalToday;
        this.breakage = breakage;
    }

    boolean isFatalToday() {
        return isFatalToday;
    }

    /** The breaker as a script's UI: a line of text, then the breakage. */
    ScriptUI ui() {
        return () -> {
            ImGui.text("breaker " + name());
            breakage.run();
        };
    }

    private static void openAndLeakPopup() {
        ImGui.openPopup("leaked-popup");
        if (ImGui.beginPopup("leaked-popup")) {
            ImGui.text("inside a popup that is never ended");
        }
    }
}
