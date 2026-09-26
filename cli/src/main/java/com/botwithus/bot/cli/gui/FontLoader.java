package com.botwithus.bot.cli.gui;

import imgui.ImFont;
import imgui.ImFontAtlas;
import imgui.ImFontConfig;
import imgui.ImGui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Builds the font atlas: the Advanced-mode default font first (so every panel
 * that never pushes a font keeps its size), then the redesign's token fonts.
 * Every font has Font Awesome merged in, so icons and text share a line.
 */
public final class FontLoader {

    private static final Logger log = LoggerFactory.getLogger(FontLoader.class);

    private static final String INTER_REGULAR = "/fonts/Inter-Regular.ttf";
    private static final String INTER_MEDIUM = "/fonts/Inter-Medium.ttf";
    private static final String MONO = "/fonts/JetBrainsMono-Regular.ttf";
    private static final String ICONS = "/fonts/fa-solid-900.ttf";
    private static final float ICON_SCALE = 0.85f;
    private static final int OVERSAMPLE = 2;
    private static final int DEFAULT_OVERSAMPLE = 3;

    /**
     * Latin-1 plus the punctuation, arrows and minus sign the UI copy uses
     * ("…", "·", "↑↓", "−", curly quotes). Pairs of inclusive ranges, zero-terminated.
     */
    private static final short[] TEXT_RANGES = {
        0x0020, 0x00FF, 0x2010, 0x205E, 0x2190, 0x21FF, 0x2212, 0x2212, 0,
    };

    /** Font Awesome 6 solid: U+E000..U+F8FF. */
    private static final short[] ICON_RANGES = {(short) 0xE000, (short) 0xF8FF, 0};

    private FontLoader() {}

    /**
     * Clears the atlas and loads every font.
     *
     * @param scale         DPI scale; sizes are rounded to whole pixels after scaling
     * @param advancedPx    size of the Advanced-mode default font at 100%
     */
    public static UiFonts loadAll(float scale, float advancedPx) {
        ImFontAtlas atlas = ImGui.getIO().getFonts();
        atlas.clear();
        byte[] regular = readFont(INTER_REGULAR);
        byte[] medium = readFont(INTER_MEDIUM);
        byte[] mono = readFont(MONO);
        byte[] icons = readFont(ICONS);
        if (regular == null) {
            regular = loadSystemFont("segoeui.ttf", "arial.ttf", "verdana.ttf");
        }
        add(atlas, regular, px(advancedPx, scale), icons, DEFAULT_OVERSAMPLE);
        byte[] mediumOrRegular = medium != null ? medium : regular;
        byte[] monoOrRegular = mono != null ? mono : regular;
        UiFonts fonts = new UiFonts(
                add(atlas, regular, px(UiFonts.CAPTION_PX, scale), icons, OVERSAMPLE),
                add(atlas, mediumOrRegular, px(UiFonts.CAPTION_PX, scale), icons, OVERSAMPLE),
                add(atlas, regular, px(UiFonts.SMALL_PX, scale), icons, OVERSAMPLE),
                add(atlas, mediumOrRegular, px(UiFonts.SMALL_PX, scale), icons, OVERSAMPLE),
                add(atlas, regular, px(UiFonts.BODY_PX, scale), icons, OVERSAMPLE),
                add(atlas, mediumOrRegular, px(UiFonts.BODY_PX, scale), icons, OVERSAMPLE),
                add(atlas, mediumOrRegular, px(UiFonts.TITLE_PX, scale), icons, OVERSAMPLE),
                add(atlas, monoOrRegular, px(UiFonts.CAPTION_PX, scale), icons, OVERSAMPLE),
                add(atlas, monoOrRegular, px(UiFonts.SMALL_PX, scale), icons, OVERSAMPLE));
        atlas.build();
        return fonts;
    }

    private static float px(float base, float scale) {
        return Math.round(base * scale);
    }

    /** Adds one text font at {@code size} with the icon font merged into it. */
    private static ImFont add(ImFontAtlas atlas, byte[] ttf, float size, byte[] icons, int oversample) {
        ImFontConfig cfg = new ImFontConfig();
        cfg.setOversampleH(oversample);
        cfg.setOversampleV(oversample);
        cfg.setPixelSnapH(true);
        ImFont font;
        if (ttf != null) {
            font = atlas.addFontFromMemoryTTF(ttf, size, cfg, TEXT_RANGES);
        } else {
            cfg.setSizePixels(size);
            font = atlas.addFontDefault(cfg);
        }
        cfg.destroy();
        if (icons != null) {
            ImFontConfig iconCfg = new ImFontConfig();
            iconCfg.setMergeMode(true);
            iconCfg.setPixelSnapH(true);
            iconCfg.setOversampleH(OVERSAMPLE);
            iconCfg.setOversampleV(OVERSAMPLE);
            atlas.addFontFromMemoryTTF(icons, Math.round(size * ICON_SCALE), iconCfg, ICON_RANGES);
            iconCfg.destroy();
        }
        return font;
    }

    private static byte[] readFont(String resourcePath) {
        try (var in = FontLoader.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            log.debug("Could not read resource font {}", resourcePath, e);
        }
        log.warn("Bundled font {} is missing; falling back", resourcePath);
        return null;
    }

    private static byte[] loadSystemFont(String... candidates) {
        String windir = System.getenv("WINDIR");
        Path fontsDir = Paths.get(windir != null ? windir : "C:\\Windows", "Fonts");
        for (String name : candidates) {
            Path p = fontsDir.resolve(name);
            if (Files.exists(p)) {
                try {
                    return Files.readAllBytes(p);
                } catch (IOException e) {
                    log.debug("Could not read system font {}", p, e);
                }
            }
        }
        return null;
    }
}
