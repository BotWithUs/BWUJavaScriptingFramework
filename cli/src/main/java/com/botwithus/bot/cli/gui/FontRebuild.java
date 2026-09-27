package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.cli.settings.TextSize;

import imgui.gl3.ImGuiImplGl3;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies a change to the text size setting. The font atlas cannot be rebuilt
 * while a frame is being drawn, so a change only marks the rebuild due, from
 * whatever thread made it; {@link #applyIfDue} does it on the render thread
 * between frames, before the next one starts.
 */
final class FontRebuild {

    private final HostSettings settings;
    private final Controls ui;
    private final float monitorScale;
    private final float advancedFontPx;
    private final AtomicBoolean isDue = new AtomicBoolean();

    /**
     * @param monitorScale   the monitor's content scale the atlas was first built at
     * @param advancedFontPx size of the Advanced-mode default font at 100%
     */
    FontRebuild(HostSettings settings, Controls ui, float monitorScale, float advancedFontPx) {
        this.settings = settings;
        this.ui = ui;
        this.monitorScale = monitorScale;
        this.advancedFontPx = advancedFontPx;
        settings.onChange(SettingKeys.TEXT_SIZE, size -> isDue.set(true));
        // The atlas was first built at the monitor's scale; a pinned size needs one rebuild before frame one.
        isDue.set(settings.get(SettingKeys.TEXT_SIZE) != TextSize.MATCH_WINDOWS);
    }

    /** The scale the text size setting asks for now. */
    float scale() {
        return TextScale.of(settings.get(SettingKeys.TEXT_SIZE), monitorScale);
    }

    /** The monitor's own scaling as a percentage, for "Match Windows (150%)". */
    int monitorPercent() {
        return TextScale.percent(monitorScale);
    }

    /** Rebuilds the atlas, its texture and the style sizes if the setting changed. Render thread, between frames. */
    void applyIfDue(ImGuiImplGl3 gl3) {
        if (!isDue.getAndSet(false)) {
            return;
        }
        float scale = scale();
        ui.useFonts(FontLoader.loadAll(scale, advancedFontPx));
        // The backend uploads the rebuilt atlas as a new texture when the next frame starts.
        gl3.destroyFontsTexture();
        ImGuiTheme.apply(scale);
    }
}
