package com.botwithus.bot.cli.gui;

import com.botwithus.bot.core.voice.ConsentRequest;
import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiPopupFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.util.Objects;
import java.util.Optional;

/**
 * The modal that asks whether a script may use the microphone. Drawn every frame from the
 * app's loop after the shell, so it shows in either mode; {@link MicrophonePrompt} holds its
 * rules, and this class only draws them.
 *
 * <p>Neither answer is the hero, and Deny is the safe default: it comes first, it is the
 * button keyboard navigation lands on, and no key presses Allow. Esc closes the prompt
 * without answering. It never takes focus from another window: like the other modals it
 * appears when the host window is next looked at.</p>
 */
final class MicrophoneConsentModal {

    private static final String POPUP_ID = "##microphone-consent";
    private static final float WIDTH_EM = 32f;
    private static final float ICON_TILE_EM = 2f;
    private static final float LINE_EM = 1.5f;
    private static final int STYLE_VARS = 3;
    private static final int STYLE_COLORS = 3;
    private static final int FLAGS = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoSavedSettings;

    private final Controls ui;
    private final MicrophonePrompt prompt;

    MicrophoneConsentModal(Controls ui, MicrophonePrompt prompt) {
        this.ui = Objects.requireNonNull(ui, "ui");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
    }

    /** Opens the modal when a question is waiting, and draws it while it is up. Render thread only. */
    void render() {
        Optional<ConsentRequest> request = prompt.frame(ImGui.getIO().getDeltaTime());
        if (request.isEmpty()) {
            closeIfOpen();
            return;
        }
        // Another modal may own the screen, and opening this one would close it. Wait for it;
        // the question waits too, and the script hears nothing meanwhile.
        if (!ImGui.isPopupOpen(POPUP_ID) && !ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopup)) {
            ImGui.openPopup(POPUP_ID);
        }
        if (!begin()) {
            return;
        }
        Optional<MicrophonePrompt.Choice> choice = content(request.get());
        if (choice.isEmpty() && ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            choice = Optional.of(MicrophonePrompt.Choice.CLOSE);
        }
        if (choice.isPresent() && prompt.choose(request.get(), choice.get())) {
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    /** The question was withdrawn (its script stopped): take the modal down. */
    private void closeIfOpen() {
        if (ImGui.isPopupOpen(POPUP_ID) && begin()) {
            ImGui.closeCurrentPopup();
            ImGui.endPopup();
        }
    }

    private boolean begin() {
        place();
        pushStyle();
        boolean isOpen = ImGui.beginPopupModal(POPUP_ID, FLAGS);
        popStyle();
        return isOpen;
    }

    private ImGuiTheme.Metrics m() {
        return ui.m();
    }

    private float fs() {
        return ImGui.getFontSize();
    }

    private void place() {
        var vp = ImGui.getMainViewport();
        float margin = m().u(5) * 2f;
        float width = Math.min(fs() * WIDTH_EM, vp.getSizeX() - margin);
        ImGui.setNextWindowSize(width, 0f);
        ImGui.setNextWindowPos(vp.getPosX() + vp.getSizeX() * 0.5f, vp.getPosY() + vp.getSizeY() * 0.5f,
                0, 0.5f, 0.5f);
    }

    private void pushStyle() {
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, m().u(5), m().u(4));
        ImGui.pushStyleVar(ImGuiStyleVar.PopupRounding, m().radiusXl());
        ImGui.pushStyleVar(ImGuiStyleVar.PopupBorderSize, m().hairline());
        Controls.pushColor(ImGuiCol.PopupBg, ImGuiTheme.COL_ELEVATED);
        Controls.pushColor(ImGuiCol.Border, ImGuiTheme.COL_BORDER);
        Controls.pushColor(ImGuiCol.ModalWindowDimBg, ImGuiTheme.COL_SCRIM);
    }

    private static void popStyle() {
        ImGui.popStyleColor(STYLE_COLORS);
        ImGui.popStyleVar(STYLE_VARS);
    }

    /** Draws the body and the buttons; returns the choice if a button was pressed. */
    private Optional<MicrophonePrompt.Choice> content(ConsentRequest request) {
        MicrophonePrompt.Text text = MicrophonePrompt.text(request);
        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float width = ImGui.getContentRegionAvailX();
        float tile = fs() * ICON_TILE_EM;
        ui.iconTile(draw, x, y, tile, Icons.MICROPHONE, ImGuiTheme.COL_INFO, ImGuiTheme.COL_INFO_SOFT);
        ui.textCentredY(draw, ui.fonts().bodyMedium(), x + tile + m().u(3), y, tile, ImGuiTheme.COL_FG,
                text.title());
        float cy = y + tile + m().u(3);
        cy = paragraph(draw, ui.fonts().body(), x, cy, width, ImGuiTheme.COL_FG, text.body());
        ImFont mono = ui.fonts().monoCaption();
        // Shortened from the front: a key's end (the class) is what tells two scripts apart.
        ui.text(draw, mono, x, cy, ImGuiTheme.COL_FG2, SafeText.keepTail(text.scriptKey(), width,
                key -> ui.width(mono, key)));
        cy += fs() * LINE_EM + m().u(2);
        ui.text(draw, ui.fonts().small(), x, cy, ImGuiTheme.COL_FG2, text.listLabel());
        cy += fs() * LINE_EM;
        cy = phrases(draw, text, x, cy, width) + m().u(3);
        cy = paragraph(draw, ui.fonts().small(), x, cy, width, ImGuiTheme.COL_FG2, text.footnote());
        ImGui.setCursorScreenPos(x, cy + m().u(3));
        return buttons(width);
    }

    private float paragraph(ImDrawList draw, ImFont font, float x, float y, float width, int col, String text) {
        float cy = y;
        for (String line : ui.wrap(font, text, width)) {
            ui.text(draw, font, x, cy, col, line);
            cy += fs() * LINE_EM;
        }
        return cy;
    }

    /** The phrases in an inset panel, one per line, with the count of any left out. */
    private float phrases(ImDrawList draw, MicrophonePrompt.Text text, float x, float y, float width) {
        float pad = m().u(2);
        float line = fs() * LINE_EM;
        int lines = text.phrases().size() + (text.moreLine().isPresent() ? 1 : 0);
        float bottom = y + pad * 2f + line * lines;
        draw.addRectFilled(x, y, x + width, bottom, ImGuiTheme.COL_SURFACE, m().radiusSmall());
        ImFont font = ui.fonts().small();
        float cy = y + pad;
        for (String phrase : text.phrases()) {
            ui.text(draw, font, x + pad, cy, ImGuiTheme.COL_FG, ui.ellipsize(font, phrase, width - pad * 2f));
            cy += line;
        }
        text.moreLine().ifPresent(more -> ui.text(draw, font, x + pad, y + pad + line * (lines - 1),
                ImGuiTheme.COL_FG2, more));
        return bottom;
    }

    /** Deny then Allow, right-aligned; Deny takes the keyboard focus. Disabled until armed. */
    private Optional<MicrophonePrompt.Choice> buttons(float width) {
        String deny = "Deny";
        String allow = "Allow";
        float gap = m().u(2);
        float total = ui.buttonWidth(null, deny, Controls.Tone.GHOST)
                + ui.buttonWidth(Icons.MICROPHONE, allow, Controls.Tone.SOFT) + gap;
        ImGui.setCursorScreenPos(ImGui.getCursorScreenPosX() + Math.max(0f, width - total),
                ImGui.getCursorScreenPosY());
        boolean isArmed = prompt.isArmed();
        Optional<MicrophonePrompt.Choice> pressed = Optional.empty();
        if (ui.button("##mic-deny", null, deny, Controls.Tone.GHOST, isArmed)) {
            pressed = Optional.of(MicrophonePrompt.Choice.DENY);
        }
        ImGui.setItemDefaultFocus();
        ImGui.sameLine(0f, gap);
        if (ui.button("##mic-allow", Icons.MICROPHONE, allow, Controls.Tone.SOFT, isArmed)) {
            pressed = Optional.of(MicrophonePrompt.Choice.ALLOW);
        }
        return pressed;
    }
}
