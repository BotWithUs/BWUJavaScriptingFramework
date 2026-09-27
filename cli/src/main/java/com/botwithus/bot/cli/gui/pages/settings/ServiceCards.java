package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.gui.Controls;
import com.botwithus.bot.cli.gui.Controls.Tone;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.core.alerts.AlertService;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import imgui.type.ImString;

import java.util.List;
import java.util.Optional;

/**
 * Draws one Integrations service card: the tile, name, status and switch, and —
 * while the service is on — its fields on a darker panel, the setup hint and
 * Send test with the last result beside it. Plain settings in the card save like
 * any row; the secret box goes through {@link SecretFields}.
 */
final class ServiceCards {

    /** 32 px at 100%: the service's tile. */
    private static final float TILE_EM = 2.133f;
    /** 150 px at 100%: the field labels' column. */
    private static final float LABEL_COLUMN_EM = 10f;
    private static final float LABEL_LINE = 1.45f;
    private static final float CAPTION_LINE = 1.5f;
    static final String CARD_ID = "card:";
    private static final String SECRET_ID = "secret:";
    private static final String NTFY_TILE = "ntfy";
    private static final String OPTIONAL = " · optional";
    private static final String SEND_TEST = "Send test";
    private static final String SPACE = " ";

    private final Controls ui;
    private final SettingsWidgets widgets;
    private final RowEdits edits;
    private final SecretFields secrets;

    ServiceCards(Controls ui, SettingsWidgets widgets, RowEdits edits, SecretFields secrets) {
        this.ui = ui;
        this.widgets = widgets;
        this.edits = edits;
        this.secrets = secrets;
    }

    /** Draws the card with its top-left at (x, y); returns its height. */
    float card(SettingsItem.ServiceCard card, float x, float y, float width, SettingsModel model) {
        float h = header(card, x, y, width, model);
        if (card.isEnabled()) {
            h += panel(card, x, y + h, width, model);
        }
        return h;
    }

    // ── Header ─────────────────────────────────────────────────────────────

    private float header(SettingsItem.ServiceCard card, float x, float y, float width, SettingsModel model) {
        ImGuiTheme.Metrics m = ui.m();
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont name = ui.fonts().smallMedium();
        ImFont caption = ui.fonts().caption();
        float tile = tileSize();
        float textX = textLeft(x);
        float textW = x + width - m.u(4) - ui.toggleWidth() - m.u(5) - textX;
        List<String> lines = ui.wrap(caption, card.description(), textW);
        float nameH = name.getFontSize() * LABEL_LINE;
        float textH = nameH + lines.size() * captionLine();
        float h = Math.max(tile, textH) + m.u(3) * 2f;
        tile(draw, card.service(), x + m.u(4), y + (h - tile) * 0.5f, tile);
        float ty = y + (h - textH) * 0.5f;
        String label = card.service().label();
        ui.text(draw, name, textX, ty, ImGuiTheme.COL_FG, label);
        status(draw, card.chip(), textX + ui.width(name, label) + m.u(2), ty, name.getFontSize());
        float cy = ty + nameH;
        for (String line : lines) {
            ui.text(draw, caption, textX, cy, ImGuiTheme.COL_FG2, line);
            cy += captionLine();
        }
        String id = CARD_ID + card.enabledKey();
        ImGui.setCursorScreenPos(x + width - m.u(4) - ui.toggleWidth(), y + (h - m.controlHeight()) * 0.5f);
        if (widgets.toggle(id, card.isEnabled())) {
            edits.accept(id, model.edit(card.enabledKey(), String.valueOf(!card.isEnabled())));
        }
        return h;
    }

    /** ntfy has no mark, so its tile is its name, as in the design; the others get a stand-in glyph. */
    private void tile(ImDrawList draw, AlertService service, float x, float y, float size) {
        switch (service) {
            case NTFY -> nameTile(draw, x, y, size);
            case SLACK -> ui.iconTile(draw, x, y, size, IntegrationIcons.HASHTAG, ImGuiTheme.COL_FG,
                    ImGuiTheme.COL_ELEVATED);
            case DISCORD -> ui.iconTile(draw, x, y, size, IntegrationIcons.COMMENTS, ImGuiTheme.COL_FG,
                    ImGuiTheme.COL_ELEVATED);
        }
    }

    private void nameTile(ImDrawList draw, float x, float y, float size) {
        draw.addRectFilled(x, y, x + size, y + size, ImGuiTheme.COL_ELEVATED, ui.m().radius());
        ImFont mono = ui.fonts().monoCaption();
        ui.textCentredY(draw, mono, x + (size - ui.width(mono, NTFY_TILE)) * 0.5f, y, size, ImGuiTheme.COL_FG,
                NTFY_TILE);
    }

    /** A dot and a word in the status's colour, centred on a line {@code h} tall. */
    private void status(ImDrawList draw, StatusChip chip, float x, float y, float h) {
        ImGuiTheme.Metrics m = ui.m();
        int col = toneColour(chip.tone());
        float r = m.dot() * 0.5f;
        draw.addCircleFilled(x + r, y + h * 0.5f, r, col);
        ui.textCentredY(draw, ui.fonts().captionMedium(), x + m.dot() + m.u(1.5f), y, h, col, chip.label());
    }

    static int toneColour(StatusChip.Tone tone) {
        return switch (tone) {
            case OK -> ImGuiTheme.COL_ACCENT;
            case ERROR -> ImGuiTheme.COL_DANGER;
            case IDLE -> ImGuiTheme.COL_FG3;
            case BUSY -> ImGuiTheme.COL_INFO;
        };
    }

    // ── Fields panel ───────────────────────────────────────────────────────

    /** The darker panel under a card that is on; sized first so its background goes under its content. */
    private float panel(SettingsItem.ServiceCard card, float x, float y, float width, SettingsModel model) {
        ImGuiTheme.Metrics m = ui.m();
        float fieldX = textLeft(x) + ui.m().fontSize() * LABEL_COLUMN_EM + m.u(4);
        float fieldW = x + width - m.u(4) - fieldX;
        List<String> hint = ui.wrap(ui.fonts().caption(), card.hint(), fieldW);
        float h = m.u(3) + hint.size() * captionLine() + m.u(2) + m.controlSmallHeight() + m.u(4);
        for (CardField field : card.fields()) {
            h += fieldHeight(field, fieldW) + m.u(2);
        }
        ImDrawList draw = ImGui.getWindowDrawList();
        // Inside the section box's outline, which is drawn underneath the rows.
        float in = m.hairline();
        draw.addRectFilled(x + in, y, x + width - in, y + h, ImGuiTheme.COL_BG);
        draw.addLine(x, y, x + width, y, ImGuiTheme.COL_BORDER, m.hairline());
        float cy = y + m.u(3);
        for (CardField field : card.fields()) {
            fieldRow(field, textLeft(x), fieldX, cy, fieldW, model);
            cy += fieldHeight(field, fieldW) + m.u(2);
        }
        for (String line : hint) {
            ui.text(draw, ui.fonts().caption(), fieldX, cy, ImGuiTheme.COL_FG3, line);
            cy += captionLine();
        }
        testRow(card, fieldX, cy + m.u(2), fieldW, model);
        return h;
    }

    private float fieldHeight(CardField field, float fieldW) {
        return ui.m().controlHeight() + errorLines(field, fieldW).size() * captionLine();
    }

    private void fieldRow(CardField field, float labelX, float fieldX, float y, float fieldW, SettingsModel model) {
        ImDrawList draw = ImGui.getWindowDrawList();
        ImFont caption = ui.fonts().caption();
        float h = ui.m().controlHeight();
        ui.textCentredY(draw, caption, labelX, y, h, ImGuiTheme.COL_FG2, field.label());
        if (field.isOptional()) {
            ui.textCentredY(draw, caption, labelX + ui.width(caption, field.label()), y, h, ImGuiTheme.COL_FG3,
                    OPTIONAL);
        }
        ImGui.setCursorScreenPos(fieldX, y);
        switch (field) {
            case CardField.Setting setting -> settingBox(setting, fieldW, model);
            case CardField.SecretBox box -> secretBox(box, fieldX, y, fieldW, model);
            case CardField.Toggle toggle -> {
                String id = CARD_ID + toggle.keyName();
                if (widgets.toggle(id, toggle.isOn())) {
                    edits.accept(id, model.edit(toggle.keyName(), String.valueOf(!toggle.isOn())));
                }
            }
        }
        float cy = y + h;
        for (String line : errorLines(field, fieldW)) {
            ui.text(draw, caption, fieldX, cy, ImGuiTheme.COL_DANGER, line);
            cy += captionLine();
        }
    }

    private void settingBox(CardField.Setting setting, float width, SettingsModel model) {
        String id = CARD_ID + setting.keyName();
        ImString buffer = edits.buffer(id, setting.text());
        SettingsWidgets.Field field = widgets.hintedField(id, buffer, setting.placeholder(), false, width,
                ui.m().controlHeight(), edits.error(id).isPresent());
        edits.track(id, field.isActive());
        if (field.isCommitted()) {
            edits.accept(id, model.edit(setting.keyName(), buffer.get()));
        }
    }

    /** The masked box and its Show / Hide button. */
    private void secretBox(CardField.SecretBox box, float x, float y, float width, SettingsModel model) {
        ImGuiTheme.Metrics m = ui.m();
        AlertService service = box.service();
        float h = m.controlHeight();
        float boxW = width - h - m.u(1);
        boolean isRevealed = secrets.isRevealed(service);
        SettingsWidgets.Field field = widgets.hintedField(CARD_ID + SECRET_ID + service.id(), secrets.buffer(service),
                secrets.hint(box), !isRevealed, boxW, h, secrets.error(service).isPresent());
        if (field.isCommitted()) {
            secrets.commit(service, model);
        }
        ImGui.setCursorScreenPos(x + boxW + m.u(1), y);
        String icon = isRevealed ? IntegrationIcons.EYE_SLASH : Icons.EYE;
        if (ui.button("##reveal-" + service.id(), icon, "", Tone.ICON, true, h)) {
            secrets.toggleReveal(service, model);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip((isRevealed ? "Hide " : "Show ") + box.label());
        }
    }

    private List<String> errorLines(CardField field, float width) {
        Optional<String> error = switch (field) {
            case CardField.Setting setting -> edits.error(CARD_ID + setting.keyName());
            case CardField.SecretBox box -> secrets.error(box.service());
            case CardField.Toggle toggle -> edits.error(CARD_ID + toggle.keyName());
        };
        return error.map(e -> ui.wrap(ui.fonts().caption(), e, width)).orElse(List.of());
    }

    /** Send test, and how the last send went beside it. */
    private void testRow(SettingsItem.ServiceCard card, float x, float y, float width, SettingsModel model) {
        ImGuiTheme.Metrics m = ui.m();
        float h = m.controlSmallHeight();
        AlertService service = card.service();
        ImGui.setCursorScreenPos(x, y);
        if (ui.button("##test-" + service.id(), IntegrationIcons.PAPER_PLANE, SEND_TEST, Tone.GHOST,
                card.canSendTest(), h)) {
            model.sendTest(service);
        }
        float rx = x + ui.buttonWidth(IntegrationIcons.PAPER_PLANE, SEND_TEST, Tone.GHOST) + m.u(3);
        card.result().ifPresent(result -> result(result, rx, y, h, x + width - rx));
    }

    private void result(ResultLine result, float x, float y, float h, float room) {
        ImDrawList draw = ImGui.getWindowDrawList();
        boolean isError = result.tone() == StatusChip.Tone.ERROR;
        ImFont font = isError ? ui.fonts().monoCaption() : ui.fonts().caption();
        int col = result.tone() == StatusChip.Tone.IDLE ? ImGuiTheme.COL_FG2 : toneColour(result.tone());
        float cx = x;
        if (result.tone() == StatusChip.Tone.OK) {
            String check = Icons.CHECK + SPACE;
            ui.textCentredY(draw, ui.fonts().caption(), cx, y, h, col, check);
            cx += ui.width(ui.fonts().caption(), check);
        }
        String text = ui.ellipsize(font, result.text(), Math.max(0f, x + room - cx));
        ui.textCentredY(draw, font, cx, y, h, col, text);
        if (!text.equals(result.text()) && ImGui.isMouseHoveringRect(cx, y, cx + ui.width(font, text), y + h)) {
            ImGui.setTooltip(result.text());
        }
    }

    // ── Shared ─────────────────────────────────────────────────────────────

    private float tileSize() {
        return ui.m().fontSize() * TILE_EM;
    }

    /** Where the card's words start: past the tile, as the fields' labels do. */
    private float textLeft(float x) {
        return x + ui.m().u(4) + tileSize() + ui.m().u(3);
    }

    private float captionLine() {
        return ui.fonts().caption().getFontSize() * CAPTION_LINE;
    }
}
