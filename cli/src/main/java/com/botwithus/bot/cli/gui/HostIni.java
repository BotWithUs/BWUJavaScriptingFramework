package com.botwithus.bot.cli.gui;

import imgui.ImGui;
import imgui.ImGuiIO;

import java.nio.file.Path;

/**
 * The host's window and dock layout file ({@code imgui.ini}), loaded and saved
 * by hand. Dear ImGui is never given its path through {@code io.IniFilename}.
 *
 * <p><b>Why by hand: the process must never call
 * {@code ImGuiIO.setIniFilename} with a non-null value.</b> imgui-java 1.90's
 * setter frees the field's previous value whenever the last value <em>any</em>
 * context was given was non-null: it tracks that in one process-wide static, not
 * per context. A fresh context's {@code IniFilename} is Dear ImGui's string
 * literal {@code "imgui.ini"}. So once the host had set its path that way, the
 * first {@code setIniFilename(null)} on a script UI's sandbox context freed a
 * literal and the process died of heap corruption ({@code 0xC0000374}). Each
 * sandbox needs that null, so that it neither reads nor writes an
 * {@code imgui.ini} in the working directory. Keeping every call null keeps the
 * static disarmed.</p>
 *
 * <p>The behaviour is otherwise what {@code io.IniFilename} gave: the file is
 * read before the first frame, and written when Dear ImGui asks for it (its
 * {@code IniSavingRate} after a change) and when the host shuts down.</p>
 */
public final class HostIni {

    private final Path file;

    private HostIni(Path file) {
        this.file = file;
    }

    /**
     * Takes over the current context's layout file and loads {@code file}, if it
     * exists. Call before the first frame.
     */
    public static HostIni attach(Path file) {
        ImGui.getIO().setIniFilename(null);
        ImGui.loadIniSettingsFromDisk(file.toString());
        return new HostIni(file);
    }

    /** Once per frame: writes the file if Dear ImGui has marked the layout changed. */
    public void saveIfWanted() {
        ImGuiIO io = ImGui.getIO();
        if (io.getWantSaveIniSettings()) {
            save();
            io.setWantSaveIniSettings(false);
        }
    }

    /** Writes the file now: at shutdown, before the context is destroyed. */
    public void save() {
        ImGui.saveIniSettingsToDisk(file.toString());
    }
}
