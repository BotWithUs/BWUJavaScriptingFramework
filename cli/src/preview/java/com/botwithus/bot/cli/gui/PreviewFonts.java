package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.settings.PreviewSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.cli.settings.TextSize;

import imgui.gl3.ImGuiImplGl3;

import java.util.Map;

/**
 * DEV ONLY. Rebuilds the preview's fonts through the real {@link FontRebuild},
 * as a change to the text size setting does in the app, so a scenario can
 * capture the page at another size. Call between frames. Nothing here ships.
 */
public final class PreviewFonts {

    private static final float MONITOR_SCALE = 1f;

    private PreviewFonts() {
    }

    public static void rebuild(Controls ui, ImGuiImplGl3 gl3, TextSize size, float defaultFontPx) {
        FontRebuild rebuild = new FontRebuild(PreviewSettings.inMemory(Map.of(SettingKeys.TEXT_SIZE.name(),
                size.name())), ui, MONITOR_SCALE, defaultFontPx);
        rebuild.applyIfDue(gl3);
    }
}
