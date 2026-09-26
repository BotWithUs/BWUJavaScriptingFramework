package com.botwithus.bot.cli.gui;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiComboFlags;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;

/**
 * The redesign's widget kit: buttons, chips, the segmented control, the search
 * box, keyboard hints and form inputs, drawn from the design tokens in
 * {@link ImGuiTheme} with the fonts in {@link UiFonts}.
 *
 * <p>Every control lays itself out at the ImGui cursor and advances it, like a
 * stock widget. Ids must be unique per frame; hover animation state is keyed on
 * them.</p>
 */
public final class Controls {

    /** Visual weight of a button, from most to least prominent. */
    public enum Tone { PRIMARY, SOFT, GHOST, STOP, ICON }

    /**
     * One segment of a {@link #segmented} control.
     *
     * @param count shown after the label in mono, or {@code null}
     * @param alert draws a red dot after the label instead of the count
     */
    public record Segment(String label, String count, boolean alert) {
        public static Segment of(String label) {
            return new Segment(label, null, false);
        }
    }

    private static final float ICON_GAP_EM = 0.467f;
    private static final float SEG_INSET_PX = 2f;
    private static final float KBD_PAD_X_EM = 0.333f;
    private static final float KBD_PAD_Y_EM = 0.2f;
    private static final float KBD_BOTTOM_PX = 2f;
    private static final float SEG_COUNT_GAP_EM = 0.333f;
    private static final float TOGGLE_W_EM = 2.133f;
    private static final float TOGGLE_H_EM = 1.2f;
    private static final float TOGGLE_KNOB_INSET_PX = 2f;
    private static final float HOVER_SPEED = 1f / ImGuiTheme.DURATION_FAST_S;
    private static final int OPAQUE = 0xFF;
    private static final int ALPHA_SHIFT = 24;
    private static final int RGB_MASK = 0x00FFFFFF;

    private final UiFonts fonts;

    public Controls(UiFonts fonts) {
        this.fonts = fonts;
    }

    public UiFonts fonts() {
        return fonts;
    }

    public ImGuiTheme.Metrics m() {
        return fonts.metrics();
    }

    // ── Text ───────────────────────────────────────────────────────────────

    public float width(ImFont font, String text) {
        return font.calcTextSizeAX(font.getFontSize(), Float.MAX_VALUE, 0f, text);
    }

    /** Draws {@code text} with {@code font} at its own size, top-left at (x, y). */
    public void text(ImDrawList draw, ImFont font, float x, float y, int col, String text) {
        draw.addText(font, Math.round(font.getFontSize()), x, y, col, text);
    }

    /** Draws {@code text} vertically centred in a band of height {@code h} starting at {@code y}. */
    public void textCentredY(ImDrawList draw, ImFont font, float x, float y, float h, int col, String text) {
        text(draw, font, x, y + (h - font.getFontSize()) * 0.5f, col, text);
    }

    /** Shortens {@code text} with an ellipsis until it fits {@code maxWidth}. */
    public String ellipsize(ImFont font, String text, float maxWidth) {
        if (width(font, text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (width(font, text.substring(0, mid) + ellipsis) <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo) + ellipsis;
    }

    /** Greedy word wrap of {@code text} into lines no wider than {@code maxWidth}. */
    public List<String> wrap(ImFont font, String text, float maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && width(font, candidate) > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** Draws wrapped lines centred on {@code centreX}; returns the height used. */
    public float centredParagraph(ImDrawList draw, ImFont font, float centreX, float y, float maxWidth,
                                  float lineHeight, int col, String text) {
        float cy = y;
        for (String line : wrap(font, text, maxWidth)) {
            text(draw, font, centreX - width(font, line) * 0.5f, cy, col, line);
            cy += lineHeight;
        }
        return cy - y;
    }

    // ── Buttons ────────────────────────────────────────────────────────────

    public float buttonWidth(String icon, String label, Tone tone) {
        ImGuiTheme.Metrics m = m();
        if (tone == Tone.ICON) {
            return m.controlHeight();
        }
        float w = m.u(3) * 2f + width(fonts.smallMedium(), label);
        if (icon != null) {
            w += width(fonts.small(), icon) + fonts.body().getFontSize() * ICON_GAP_EM;
        }
        return w;
    }

    public boolean button(String id, String icon, String label, Tone tone, boolean enabled) {
        return button(id, icon, label, tone, enabled, m().controlHeight());
    }

    /**
     * A token-styled button at the cursor.
     *
     * @param icon  Font Awesome glyph drawn before the label, or {@code null}
     * @param label button text; ignored for {@link Tone#ICON}, which draws only the icon
     */
    public boolean button(String id, String icon, String label, Tone tone, boolean enabled, float height) {
        float w = buttonWidth(icon, label, tone);
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImGui.beginDisabled(!enabled);
        boolean clicked = ImGui.invisibleButton(id, w, height);
        boolean hovered = enabled && ImGui.isItemHovered();
        boolean held = enabled && ImGui.isItemActive();
        ImGui.endDisabled();
        float t = Motion.step("btn:" + id, hovered ? 1f : 0f, HOVER_SPEED);
        float alpha = enabled ? 1f : ImGuiTheme.DISABLED_ALPHA;
        paintButton(ImGui.getWindowDrawList(), tone, x, y, w, height, t, held, alpha);
        paintButtonLabel(ImGui.getWindowDrawList(), tone, icon, label, x, y, w, height, t, alpha);
        return clicked && enabled;
    }

    private void paintButton(ImDrawList draw, Tone tone, float x, float y, float w, float h,
                             float hoverT, boolean held, float alpha) {
        float r = m().radius();
        int bg = switch (tone) {
            case PRIMARY -> held ? ImGuiTheme.COL_ACCENT_PRESS
                    : lerp(ImGuiTheme.COL_ACCENT, ImGuiTheme.COL_ACCENT_HOVER, hoverT);
            case SOFT -> lerp(ImGuiTheme.COL_ACCENT_SOFT, ImGuiTheme.COL_ACCENT_SOFT_HOVER, hoverT);
            case STOP -> lerp(ImGuiTheme.COL_DANGER_SOFT, ImGuiTheme.COL_DANGER_SOFT_HOVER, hoverT);
            case GHOST, ICON -> scaleAlpha(ImGuiTheme.COL_ELEVATED, hoverT);
        };
        draw.addRectFilled(x, y, x + w, y + h, scaleAlpha(bg, alpha), r);
        if (tone == Tone.GHOST) {
            int border = lerp(ImGuiTheme.COL_BORDER, ImGuiTheme.COL_BORDER_HOVER, hoverT);
            draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, scaleAlpha(border, alpha), r);
        }
    }

    private void paintButtonLabel(ImDrawList draw, Tone tone, String icon, String label,
                                  float x, float y, float w, float h, float hoverT, float alpha) {
        int fg = switch (tone) {
            case PRIMARY -> ImGuiTheme.COL_ON_ACCENT;
            case SOFT -> ImGuiTheme.COL_ACCENT;
            case STOP -> ImGuiTheme.COL_DANGER;
            case GHOST -> ImGuiTheme.COL_FG;
            case ICON -> lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_FG, hoverT);
        };
        fg = scaleAlpha(fg, alpha);
        if (tone == Tone.ICON) {
            float iw = width(fonts.small(), icon);
            textCentredY(draw, fonts.small(), x + (w - iw) * 0.5f, y, h, fg, icon);
            return;
        }
        float cx = x + m().u(3);
        if (icon != null) {
            textCentredY(draw, fonts.small(), cx, y, h, fg, icon);
            cx += width(fonts.small(), icon) + fonts.body().getFontSize() * ICON_GAP_EM;
        }
        textCentredY(draw, fonts.smallMedium(), cx, y, h, fg, label);
    }

    // ── Chips, tiles, kbd ──────────────────────────────────────────────────

    public float chipWidth(String label) {
        ImGuiTheme.Metrics m = m();
        return m.u(2) * 2f + m.dot() + m.u(1.5f) + width(fonts.captionMedium(), label);
    }

    /** A status chip — coloured dot plus label on a soft tint — with its top-left at (x, y). */
    public void chip(ImDrawList draw, float x, float y, String label, int fg, int bg, float dotAlpha) {
        ImGuiTheme.Metrics m = m();
        float w = chipWidth(label);
        float h = m.chipHeight();
        draw.addRectFilled(x, y, x + w, y + h, bg, m.radiusSmall());
        float dotR = m.dot() * 0.5f;
        draw.addCircleFilled(x + m.u(2) + dotR, y + h * 0.5f, dotR, scaleAlpha(fg, dotAlpha));
        textCentredY(draw, fonts.captionMedium(), x + m.u(2) + m.dot() + m.u(1.5f), y, h, fg, label);
    }

    /** A square rounded tile with a centred icon, like the card's script icon. */
    public void iconTile(ImDrawList draw, float x, float y, float size, String icon, int fg, int bg) {
        draw.addRectFilled(x, y, x + size, y + size, bg, m().radius());
        ImFont font = fonts.body();
        float iw = width(font, icon);
        text(draw, font, x + (size - iw) * 0.5f, y + (size - font.getFontSize()) * 0.5f, fg, icon);
    }

    public float kbdWidth(String keys) {
        return width(fonts.monoCaption(), keys) + fonts.body().getFontSize() * KBD_PAD_X_EM * 2f;
    }

    public float kbdHeight() {
        return fonts.monoCaption().getFontSize() + fonts.body().getFontSize() * KBD_PAD_Y_EM * 2f
                + KBD_BOTTOM_PX;
    }

    /** A keyboard-key hint with its top-left at (x, y). */
    public void kbd(ImDrawList draw, float x, float y, String keys) {
        float w = kbdWidth(keys);
        float h = kbdHeight();
        float r = m().radiusSmall();
        draw.addRectFilled(x, y, x + w, y + h, ImGuiTheme.COL_BORDER, r);
        draw.addRectFilled(x + 1f, y + 1f, x + w - 1f, y + h - KBD_BOTTOM_PX, ImGuiTheme.COL_BG, r);
        float padX = fonts.body().getFontSize() * KBD_PAD_X_EM;
        textCentredY(draw, fonts.monoCaption(), x + padX, y, h - KBD_BOTTOM_PX + 1f, ImGuiTheme.COL_FG2, keys);
    }

    // ── Segmented control ──────────────────────────────────────────────────

    public float segmentedWidth(List<Segment> segments) {
        float w = SEG_INSET_PX * 2f + SEG_INSET_PX * (segments.size() - 1);
        for (Segment s : segments) {
            w += segmentInnerWidth(s);
        }
        return w;
    }

    private float segmentInnerWidth(Segment s) {
        float w = m().u(3) * 2f + width(fonts.smallMedium(), s.label());
        return w + suffixWidth(s);
    }

    private float suffixWidth(Segment s) {
        float gap = fonts.body().getFontSize() * SEG_COUNT_GAP_EM;
        if (s.alert()) {
            return gap + m().dot();
        }
        return s.count() != null ? gap + width(fonts.monoCaption(), s.count()) : 0f;
    }

    /**
     * A segmented control at the cursor. With {@code fullWidth > 0} every segment
     * gets an equal share of that width; otherwise each is sized to its label.
     *
     * @return the index clicked this frame, or {@code -1}
     */
    public int segmented(String id, List<Segment> segments, int selected, float fullWidth, float height) {
        float x0 = ImGui.getCursorScreenPosX();
        float y0 = ImGui.getCursorScreenPosY();
        float total = fullWidth > 0f ? fullWidth : segmentedWidth(segments);
        ImDrawList draw = ImGui.getWindowDrawList();
        float r = m().radius();
        draw.addRectFilled(x0, y0, x0 + total, y0 + height, ImGuiTheme.COL_BG, r);
        draw.addRect(x0 + 0.5f, y0 + 0.5f, x0 + total - 0.5f, y0 + height - 0.5f, ImGuiTheme.COL_BORDER, r);
        float equal = (total - SEG_INSET_PX * (segments.size() + 1)) / segments.size();
        float x = x0 + SEG_INSET_PX;
        int clicked = -1;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            float w = fullWidth > 0f ? equal : segmentInnerWidth(s);
            ImGui.setCursorScreenPos(x, y0 + SEG_INSET_PX);
            if (segmentButton(id + ":" + i, s, i == selected, w, height - SEG_INSET_PX * 2f)) {
                clicked = i;
            }
            x += w + SEG_INSET_PX;
        }
        ImGui.setCursorScreenPos(x0, y0);
        ImGui.dummy(total, height);
        return clicked;
    }

    private boolean segmentButton(String id, Segment s, boolean isOn, float w, float h) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, w, h);
        float t = Motion.step("seg:" + id, ImGui.isItemHovered() ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        int bg = isOn ? ImGuiTheme.COL_ELEVATED : scaleAlpha(ImGuiTheme.COL_SURFACE, t);
        draw.addRectFilled(x, y, x + w, y + h, bg, m().radiusSmall());
        int fg = isOn ? ImGuiTheme.COL_FG : lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_FG, t);
        float content = width(fonts.smallMedium(), s.label()) + suffixWidth(s);
        float cx = x + (w - content) * 0.5f;
        textCentredY(draw, fonts.smallMedium(), cx, y, h, fg, s.label());
        cx += width(fonts.smallMedium(), s.label()) + fonts.body().getFontSize() * SEG_COUNT_GAP_EM;
        if (s.alert()) {
            float dotR = m().dot() * 0.5f;
            draw.addCircleFilled(cx + dotR, y + h * 0.5f, dotR, ImGuiTheme.COL_DANGER);
        } else if (s.count() != null) {
            textCentredY(draw, fonts.monoCaption(), cx, y, h, ImGuiTheme.COL_FG2, s.count());
        }
        return clicked;
    }

    // ── Inputs ─────────────────────────────────────────────────────────────

    /**
     * A search box: magnifier, then a borderless text input, inside a token frame
     * whose border goes to the focus colour while typing.
     *
     * @param focusNow give the input keyboard focus this frame
     * @return true when the text changed this frame
     */
    public boolean searchBox(String id, ImString buffer, String hint, float width, float height, boolean focusNow) {
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + height, ImGuiTheme.COL_BG, m().radius());
        float padX = m().u(3);
        textCentredY(draw, fonts.caption(), x + padX, y, height, ImGuiTheme.COL_FG2, Icons.SEARCH);
        float inputX = x + padX + width(fonts.caption(), Icons.SEARCH) + m().u(2);
        boolean changed = bareInput(id, buffer, hint, fonts.small(), inputX, y, x + width - padX - inputX,
                height, focusNow);
        paintFieldBorder(draw, x, y, width, height, ImGui.isItemActive(), isHovering(x, y, width, height));
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, height);
        return changed;
    }

    /** A single-line text input in a token frame. */
    public boolean textField(String id, ImString buffer, String hint, boolean mono, float width) {
        float h = m().controlHeight();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_BG, m().radius());
        float padX = m().u(3);
        ImFont font = mono ? fonts.monoSmall() : fonts.small();
        boolean changed = bareInput(id, buffer, hint, font, x + padX, y, width - padX * 2f, h, false);
        paintFieldBorder(draw, x, y, width, h, ImGui.isItemActive(), isHovering(x, y, width, h));
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, h);
        return changed;
    }

    /** A plain integer input in a token frame, mono, no step buttons. */
    public boolean numberField(String id, ImInt value, float width) {
        float h = m().controlHeight();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + h, ImGuiTheme.COL_BG, m().radius());
        float padX = m().u(3);
        ImGui.setCursorScreenPos(x + padX, y);
        boolean changed = bareInt(id, value, width - padX * 2f, h);
        paintFieldBorder(draw, x, y, width, h, ImGui.isItemActive(), isHovering(x, y, width, h));
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, h);
        return changed;
    }

    /** An integer stepper: − button, mono input, + button. */
    public boolean stepper(String id, ImInt value, float width) {
        float h = m().controlHeight();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean changed = stepButton(id + ":dec", "−", x, y, h, true);
        if (changed) {
            value.set(value.get() - 1);
        }
        float inputW = width - h * 2f;
        ImDrawList draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x + h, y, x + h + inputW, y + h, ImGuiTheme.COL_BG);
        ImGui.setCursorScreenPos(x + h + m().u(3), y);
        changed |= bareInt(id, value, inputW - m().u(3) * 2f, h);
        paintSquareBorder(draw, x + h, y, inputW, h, ImGui.isItemActive());
        if (stepButton(id + ":inc", "+", x + h + inputW, y, h, false)) {
            value.set(value.get() + 1);
            changed = true;
        }
        ImGui.setCursorScreenPos(x, y);
        ImGui.dummy(width, h);
        return changed;
    }

    private boolean stepButton(String id, String glyph, float x, float y, float h, boolean left) {
        ImGui.setCursorScreenPos(x, y);
        boolean clicked = ImGui.invisibleButton(id, h, h);
        float t = Motion.step("step:" + id, ImGui.isItemHovered() ? 1f : 0f, HOVER_SPEED);
        ImDrawList draw = ImGui.getWindowDrawList();
        int flags = left ? ImDrawFlags.RoundCornersLeft : ImDrawFlags.RoundCornersRight;
        draw.addRectFilled(x, y, x + h, y + h, lerp(ImGuiTheme.COL_ELEVATED, ImGuiTheme.COL_BORDER, t),
                m().radius(), flags);
        draw.addRect(x + 0.5f, y + 0.5f, x + h - 0.5f, y + h - 0.5f, ImGuiTheme.COL_BORDER, m().radius(), flags);
        float gw = width(fonts.body(), glyph);
        textCentredY(draw, fonts.body(), x + (h - gw) * 0.5f, y, h,
                lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_FG, t), glyph);
        return clicked;
    }

    /** A combo box styled as a token select, with a small chevron instead of ImGui's arrow button. */
    public boolean select(String id, ImInt selected, List<String> options, float width) {
        ImGuiTheme.Metrics m = m();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float h = m.controlHeight();
        ImGui.pushFont(fonts.small());
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, m.u(3), (h - fonts.small().getFontSize()) * 0.5f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, m.radius());
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, m.radius());
        pushColor(ImGuiCol.FrameBg, ImGuiTheme.COL_BG);
        pushColor(ImGuiCol.FrameBgHovered, ImGuiTheme.COL_BG);
        pushColor(ImGuiCol.PopupBg, ImGuiTheme.COL_ELEVATED);
        pushColor(ImGuiCol.Text, ImGuiTheme.COL_FG);
        ImGui.setNextItemWidth(width);
        int current = selected.get();
        String preview = current >= 0 && current < options.size() ? options.get(current) : "";
        boolean changed = false;
        boolean open = false;
        if (ImGui.beginCombo(id, preview, ImGuiComboFlags.NoArrowButton)) {
            open = true;
            for (int i = 0; i < options.size(); i++) {
                if (ImGui.selectable(options.get(i) + "##" + i, i == current)) {
                    selected.set(i);
                    changed = true;
                }
            }
            ImGui.endCombo();
        }
        ImGui.popStyleColor(4);
        ImGui.popStyleVar(3);
        ImGui.popFont();
        ImDrawList draw = ImGui.getWindowDrawList();
        paintFieldBorder(draw, x, y, width, h, open, isHovering(x, y, width, h));
        float cw = width(fonts.caption(), Icons.ANGLE_DOWN);
        textCentredY(draw, fonts.caption(), x + width - m.u(3) - cw, y, h, ImGuiTheme.COL_FG2, Icons.ANGLE_DOWN);
        return changed;
    }

    /** A label on the left and a switch on the right, spanning {@code width}. */
    public boolean toggleRow(String id, String label, boolean value, float width) {
        ImGuiTheme.Metrics m = m();
        float h = m.controlHeight();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        boolean clicked = ImGui.invisibleButton(id, width, h);
        ImDrawList draw = ImGui.getWindowDrawList();
        textCentredY(draw, fonts.small(), x, y, h, ImGuiTheme.COL_FG, label);
        float fs = fonts.body().getFontSize();
        float tw = fs * TOGGLE_W_EM;
        float th = fs * TOGGLE_H_EM;
        float tx = x + width - tw;
        float ty = y + (h - th) * 0.5f;
        float t = Motion.step("tg:" + id, value ? 1f : 0f, HOVER_SPEED);
        int track = lerp(ImGuiTheme.COL_ELEVATED, ImGuiTheme.COL_ACCENT, t);
        draw.addRectFilled(tx, ty, tx + tw, ty + th, track, th * 0.5f);
        draw.addRect(tx + 0.5f, ty + 0.5f, tx + tw - 0.5f, ty + th - 0.5f,
                lerp(ImGuiTheme.COL_BORDER, ImGuiTheme.COL_ACCENT, t), th * 0.5f);
        float knobR = th * 0.5f - TOGGLE_KNOB_INSET_PX - 1f;
        float kx0 = tx + TOGGLE_KNOB_INSET_PX + 1f + knobR;
        float kx = kx0 + (tw - (kx0 - tx) * 2f) * t;
        draw.addCircleFilled(kx, ty + th * 0.5f, knobR, lerp(ImGuiTheme.COL_FG2, ImGuiTheme.COL_ON_ACCENT, t));
        return clicked;
    }

    private boolean bareInput(String id, ImString buffer, String hint, ImFont font,
                              float x, float y, float w, float h, boolean focusNow) {
        ImGui.setCursorScreenPos(x, y);
        pushBareFrame(font, h);
        if (focusNow) {
            ImGui.setKeyboardFocusHere();
        }
        ImGui.setNextItemWidth(w);
        boolean changed = ImGui.inputTextWithHint(id, hint, buffer);
        popBareFrame();
        return changed;
    }

    private boolean bareInt(String id, ImInt value, float w, float h) {
        pushBareFrame(fonts.monoSmall(), h);
        ImGui.setNextItemWidth(w);
        boolean changed = ImGui.inputInt(id, value, 0, 0, ImGuiInputTextFlags.CharsDecimal);
        popBareFrame();
        return changed;
    }

    private void pushBareFrame(ImFont font, float h) {
        ImGui.pushFont(font);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 0f, (h - font.getFontSize()) * 0.5f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameBorderSize, 0f);
        pushColor(ImGuiCol.FrameBg, 0);
        pushColor(ImGuiCol.FrameBgHovered, 0);
        pushColor(ImGuiCol.FrameBgActive, 0);
        pushColor(ImGuiCol.TextDisabled, ImGuiTheme.COL_FG3);
        pushColor(ImGuiCol.Text, ImGuiTheme.COL_FG);
    }

    private static void popBareFrame() {
        ImGui.popStyleColor(5);
        ImGui.popStyleVar(2);
        ImGui.popFont();
    }

    private void paintFieldBorder(ImDrawList draw, float x, float y, float w, float h,
                                  boolean focused, boolean hovered) {
        int col = focused ? ImGuiTheme.COL_FOCUS
                : hovered ? ImGuiTheme.COL_BORDER_HOVER : ImGuiTheme.COL_BORDER;
        draw.addRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, col, m().radius());
    }

    private static void paintSquareBorder(ImDrawList draw, float x, float y, float w, float h, boolean focused) {
        draw.addRect(x, y + 0.5f, x + w, y + h - 0.5f, focused ? ImGuiTheme.COL_FOCUS : ImGuiTheme.COL_BORDER);
    }

    private static boolean isHovering(float x, float y, float w, float h) {
        return ImGui.isMouseHoveringRect(x, y, x + w, y + h);
    }

    // ── Colour helpers ─────────────────────────────────────────────────────

    public static void pushColor(int imGuiCol, int packed) {
        ImGui.pushStyleColor(imGuiCol, packed);
    }

    /** Multiplies a packed colour's alpha by {@code factor}. */
    public static int scaleAlpha(int packed, float factor) {
        int a = (packed >>> ALPHA_SHIFT) & OPAQUE;
        int scaled = Math.round(a * Math.max(0f, Math.min(1f, factor)));
        return (packed & RGB_MASK) | (scaled << ALPHA_SHIFT);
    }

    /** Component-wise lerp between two packed colours, alpha included. */
    public static int lerp(int from, int to, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int out = 0;
        for (int shift = 0; shift <= ALPHA_SHIFT; shift += Byte.SIZE) {
            int a = (from >>> shift) & OPAQUE;
            int b = (to >>> shift) & OPAQUE;
            out |= Math.round(a + (b - a) * k) << shift;
        }
        return out;
    }
}
