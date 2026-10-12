package com.botwithus.bot.cli.gui.scriptui;

import imgui.ImGuiStyle;
import imgui.ImVec4;

import java.util.List;

/**
 * The host's style at the moment it hands a frame to a sandbox, so the script
 * draws with exactly what it would have seen in the host's own context,
 * including any style the host pushed around the panel. Read on the UI thread
 * with the host's context current; applied on the sandbox's thread with the
 * sandbox's context current, as that frame's base style.
 *
 * <p>Every writable field of imgui-java 1.90's {@link ImGuiStyle} is copied.
 * A field added by a later binding and missed here would keep Dear ImGui's
 * default in the sandbox, which is visible but harmless.</p>
 */
final class StyleSnapshot {

    private interface FloatGet {
        float get(ImGuiStyle style);
    }

    private interface FloatSet {
        void set(ImGuiStyle style, float value);
    }

    private interface PairSet {
        void set(ImGuiStyle style, float x, float y);
    }

    private interface IntGet {
        int get(ImGuiStyle style);
    }

    private interface IntSet {
        void set(ImGuiStyle style, int value);
    }

    private interface BoolGet {
        boolean get(ImGuiStyle style);
    }

    private interface BoolSet {
        void set(ImGuiStyle style, boolean value);
    }

    private record FloatField(FloatGet get, FloatSet set) {
    }

    private record PairField(FloatGet x, FloatGet y, PairSet set) {
    }

    private record IntField(IntGet get, IntSet set) {
    }

    private record BoolField(BoolGet get, BoolSet set) {
    }

    private static final List<FloatField> FLOATS = List.of(
            new FloatField(ImGuiStyle::getAlpha, ImGuiStyle::setAlpha),
            new FloatField(ImGuiStyle::getDisabledAlpha, ImGuiStyle::setDisabledAlpha),
            new FloatField(ImGuiStyle::getWindowRounding, ImGuiStyle::setWindowRounding),
            new FloatField(ImGuiStyle::getWindowBorderSize, ImGuiStyle::setWindowBorderSize),
            new FloatField(ImGuiStyle::getChildRounding, ImGuiStyle::setChildRounding),
            new FloatField(ImGuiStyle::getChildBorderSize, ImGuiStyle::setChildBorderSize),
            new FloatField(ImGuiStyle::getPopupRounding, ImGuiStyle::setPopupRounding),
            new FloatField(ImGuiStyle::getPopupBorderSize, ImGuiStyle::setPopupBorderSize),
            new FloatField(ImGuiStyle::getFrameRounding, ImGuiStyle::setFrameRounding),
            new FloatField(ImGuiStyle::getFrameBorderSize, ImGuiStyle::setFrameBorderSize),
            new FloatField(ImGuiStyle::getIndentSpacing, ImGuiStyle::setIndentSpacing),
            new FloatField(ImGuiStyle::getColumnsMinSpacing, ImGuiStyle::setColumnsMinSpacing),
            new FloatField(ImGuiStyle::getScrollbarSize, ImGuiStyle::setScrollbarSize),
            new FloatField(ImGuiStyle::getScrollbarRounding, ImGuiStyle::setScrollbarRounding),
            new FloatField(ImGuiStyle::getGrabMinSize, ImGuiStyle::setGrabMinSize),
            new FloatField(ImGuiStyle::getGrabRounding, ImGuiStyle::setGrabRounding),
            new FloatField(ImGuiStyle::getLogSliderDeadzone, ImGuiStyle::setLogSliderDeadzone),
            new FloatField(ImGuiStyle::getTabRounding, ImGuiStyle::setTabRounding),
            new FloatField(ImGuiStyle::getTabBorderSize, ImGuiStyle::setTabBorderSize),
            new FloatField(ImGuiStyle::getTabMinWidthForCloseButton, ImGuiStyle::setTabMinWidthForCloseButton),
            new FloatField(ImGuiStyle::getSeparatorTextBorderSize, ImGuiStyle::setSeparatorTextBorderSize),
            new FloatField(ImGuiStyle::getDockingSeparatorSize, ImGuiStyle::setDockingSeparatorSize),
            new FloatField(ImGuiStyle::getMouseCursorScale, ImGuiStyle::setMouseCursorScale),
            new FloatField(ImGuiStyle::getCurveTessellationTol, ImGuiStyle::setCurveTessellationTol),
            new FloatField(ImGuiStyle::getCircleTessellationMaxError, ImGuiStyle::setCircleTessellationMaxError),
            new FloatField(ImGuiStyle::getHoverStationaryDelay, ImGuiStyle::setHoverStationaryDelay),
            new FloatField(ImGuiStyle::getHoverDelayShort, ImGuiStyle::setHoverDelayShort),
            new FloatField(ImGuiStyle::getHoverDelayNormal, ImGuiStyle::setHoverDelayNormal));

    private static final List<PairField> PAIRS = List.of(
            new PairField(ImGuiStyle::getWindowPaddingX, ImGuiStyle::getWindowPaddingY,
                    ImGuiStyle::setWindowPadding),
            new PairField(ImGuiStyle::getWindowMinSizeX, ImGuiStyle::getWindowMinSizeY,
                    ImGuiStyle::setWindowMinSize),
            new PairField(ImGuiStyle::getWindowTitleAlignX, ImGuiStyle::getWindowTitleAlignY,
                    ImGuiStyle::setWindowTitleAlign),
            new PairField(ImGuiStyle::getFramePaddingX, ImGuiStyle::getFramePaddingY,
                    ImGuiStyle::setFramePadding),
            new PairField(ImGuiStyle::getItemSpacingX, ImGuiStyle::getItemSpacingY,
                    ImGuiStyle::setItemSpacing),
            new PairField(ImGuiStyle::getItemInnerSpacingX, ImGuiStyle::getItemInnerSpacingY,
                    ImGuiStyle::setItemInnerSpacing),
            new PairField(ImGuiStyle::getCellPaddingX, ImGuiStyle::getCellPaddingY,
                    ImGuiStyle::setCellPadding),
            new PairField(ImGuiStyle::getTouchExtraPaddingX, ImGuiStyle::getTouchExtraPaddingY,
                    ImGuiStyle::setTouchExtraPadding),
            new PairField(ImGuiStyle::getButtonTextAlignX, ImGuiStyle::getButtonTextAlignY,
                    ImGuiStyle::setButtonTextAlign),
            new PairField(ImGuiStyle::getSelectableTextAlignX, ImGuiStyle::getSelectableTextAlignY,
                    ImGuiStyle::setSelectableTextAlign),
            new PairField(ImGuiStyle::getSeparatorTextAlignX, ImGuiStyle::getSeparatorTextAlignY,
                    ImGuiStyle::setSeparatorTextAlign),
            new PairField(ImGuiStyle::getSeparatorTextPaddingX, ImGuiStyle::getSeparatorTextPaddingY,
                    ImGuiStyle::setSeparatorTextPadding),
            new PairField(ImGuiStyle::getDisplayWindowPaddingX, ImGuiStyle::getDisplayWindowPaddingY,
                    ImGuiStyle::setDisplayWindowPadding),
            new PairField(ImGuiStyle::getDisplaySafeAreaPaddingX, ImGuiStyle::getDisplaySafeAreaPaddingY,
                    ImGuiStyle::setDisplaySafeAreaPadding));

    private static final List<IntField> INTS = List.of(
            new IntField(ImGuiStyle::getWindowMenuButtonPosition, ImGuiStyle::setWindowMenuButtonPosition),
            new IntField(ImGuiStyle::getColorButtonPosition, ImGuiStyle::setColorButtonPosition),
            new IntField(ImGuiStyle::getHoverFlagsForTooltipMouse, ImGuiStyle::setHoverFlagsForTooltipMouse),
            new IntField(ImGuiStyle::getHoverFlagsForTooltipNav, ImGuiStyle::setHoverFlagsForTooltipNav));

    private static final List<BoolField> BOOLS = List.of(
            new BoolField(ImGuiStyle::getAntiAliasedLines, ImGuiStyle::setAntiAliasedLines),
            new BoolField(ImGuiStyle::getAntiAliasedLinesUseTex, ImGuiStyle::setAntiAliasedLinesUseTex),
            new BoolField(ImGuiStyle::getAntiAliasedFill, ImGuiStyle::setAntiAliasedFill));

    private final float[] floats = new float[FLOATS.size()];
    private final float[] pairs = new float[PAIRS.size() * 2];
    private final int[] ints = new int[INTS.size()];
    private final boolean[] bools = new boolean[BOOLS.size()];
    private ImVec4[] colors = new ImVec4[0];

    /** Overwrites this snapshot with {@code style}'s current values. */
    void readFrom(ImGuiStyle style) {
        for (int i = 0; i < FLOATS.size(); i++) {
            floats[i] = FLOATS.get(i).get().get(style);
        }
        for (int i = 0; i < PAIRS.size(); i++) {
            pairs[2 * i] = PAIRS.get(i).x().get(style);
            pairs[2 * i + 1] = PAIRS.get(i).y().get(style);
        }
        for (int i = 0; i < INTS.size(); i++) {
            ints[i] = INTS.get(i).get().get(style);
        }
        for (int i = 0; i < BOOLS.size(); i++) {
            bools[i] = BOOLS.get(i).get().get(style);
        }
        colors = style.getColors();
    }

    /** Writes this snapshot into {@code style}. */
    void applyTo(ImGuiStyle style) {
        for (int i = 0; i < FLOATS.size(); i++) {
            FLOATS.get(i).set().set(style, floats[i]);
        }
        for (int i = 0; i < PAIRS.size(); i++) {
            PAIRS.get(i).set().set(style, pairs[2 * i], pairs[2 * i + 1]);
        }
        for (int i = 0; i < INTS.size(); i++) {
            INTS.get(i).set().set(style, ints[i]);
        }
        for (int i = 0; i < BOOLS.size(); i++) {
            BOOLS.get(i).set().set(style, bools[i]);
        }
        style.setColors(colors);
    }
}
