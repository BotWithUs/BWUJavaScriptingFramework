package com.botwithus.bot.cli.gui.scriptui;

import java.util.Objects;

/**
 * A Dear ImGui assertion that failed: the checked expression and where in
 * Dear ImGui's own sources the check lives. The file is reported by its base
 * name, since the full path is the build machine's and means nothing here.
 *
 * @param expression the expression that was false, as Dear ImGui wrote it
 * @param file       the source file of the check, as the native build saw it
 * @param line       the line of the check
 */
public record ImGuiAssertion(String expression, String file, int line) {

    public ImGuiAssertion {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(file, "file");
    }

    /** {@code expression (imgui.cpp:1234)}: what a log line or the panel shows. */
    public String describe() {
        return expression + " (" + fileName() + ":" + line + ")";
    }

    private String fileName() {
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return file.substring(slash + 1);
    }
}
