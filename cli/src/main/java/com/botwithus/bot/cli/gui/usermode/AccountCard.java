package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.gui.GuiHelpers;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.core.loader.BwuJagexAccount;
import com.botwithus.bot.core.loader.BwuJagexCharacter;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

import java.time.Instant;
import java.util.List;

/**
 * Renders a single Jagex account card in User Mode — matching the
 * {@link ClientCard} visual language with surface background, rounded
 * border, and status indicators.
 *
 * <p>Three card variants:
 * <ul>
 *   <li>Account card — shows characters, session, launch</li>
 *   <li>"Add Jagex" action card — dashed outline, opens OAuth flow</li>
 *   <li>"Restore" action card — dashed outline, restores from Credential Manager</li>
 * </ul>
 */
final class AccountCard {

    /** Max characters shown inline before truncating. */
    private static final int MAX_VISIBLE_CHARS = 4;

    // ─── Account card ──────────────────────────────────────────────────────

    /**
     * Render a Jagex account card. Returns a non-null {@link Action} when the
     * user interacted with something.
     */
    Action render(BwuJagexAccount account, float cardWidth, int index, boolean busy) {
        Action result = null;
        float cardHeight = estimateHeight(account);

        ImGui.pushStyleColor(ImGuiCol.ChildBg,
                ImGuiTheme.SURFACE_R, ImGuiTheme.SURFACE_G, ImGuiTheme.SURFACE_B, 1f);
        ImGui.pushStyleColor(ImGuiCol.Border,
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.4f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 8f);

        ImGui.beginChild("##acctCard" + index, cardWidth, cardHeight, true);
        ImGui.popStyleColor(2);
        ImGui.popStyleVar();

        // ── Header: shield + display label ──
        renderHeader(account);

        ImGui.spacing();
        GuiHelpers.subtleSeparator();
        ImGui.spacing();

        // ── Character list with selection ──
        result = renderCharacters(account, index, busy);

        ImGui.spacing();

        // ── Session status ──
        renderSessionLine(account);

        ImGui.spacing();
        ImGui.spacing();

        // ── Launch button ──
        if (busy) {
            ImGui.beginDisabled();
        }

        ImGui.pushStyleColor(ImGuiCol.Text, 0.04f, 0.04f, 0.1f, 1f);
        ImGui.pushStyleColor(ImGuiCol.Button,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.85f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                ImGuiTheme.ACCENT_R * 0.85f, ImGuiTheme.ACCENT_G * 0.85f, ImGuiTheme.ACCENT_B * 0.85f, 1f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                ImGuiTheme.ACCENT_R * 0.7f, ImGuiTheme.ACCENT_G * 0.7f, ImGuiTheme.ACCENT_B * 0.7f, 1f);

        float btnW = ImGui.getContentRegionAvailX();
        if (ImGui.button(Icons.PLAY + "  Launch##acct" + index, btnW, 0)) {
            result = new Action(Action.Type.LAUNCH_JAGEX, account.uuid(), account.selectedCharacter());
        }
        ImGui.popStyleColor(4);

        if (busy) {
            ImGui.endDisabled();
        }

        // ── Context actions (small, below launch) ──
        if (busy) {
            ImGui.beginDisabled();
        }

        if (ImGui.smallButton(Icons.ROTATE + "##refresh" + index)) {
            result = new Action(Action.Type.REFRESH_CHARACTERS, account.uuid(), -1);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Refresh characters");
        }

        ImGui.sameLine(0, 6);
        if (ImGui.smallButton(Icons.SHIELD + "##session" + index)) {
            result = new Action(Action.Type.ENSURE_SESSION, account.uuid(), -1);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Refresh session");
        }

        ImGui.sameLine(0, 6);
        ImGui.pushStyleColor(ImGuiCol.Text,
                ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.7f);
        ImGui.pushStyleColor(ImGuiCol.Button, 0, 0, 0, 0);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.1f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 0.2f);
        if (ImGui.smallButton(Icons.TRASH + "##remove" + index)) {
            result = new Action(Action.Type.REMOVE_JAGEX, account.uuid(), -1);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Remove account");
        }
        ImGui.popStyleColor(4);

        if (busy) {
            ImGui.endDisabled();
        }

        ImGui.endChild();
        return result;
    }

    // ─── "Add" action card ─────────────────────────────────────────────────

    /**
     * Render an "add account" placeholder card with dashed border and a
     * centered icon + label. Returns true if clicked.
     */
    boolean renderAddCard(String label, String icon, float cardWidth, float cardHeight, String id) {
        boolean clicked = false;

        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();

        // Invisible button covering the card area for the click target
        ImGui.pushStyleColor(ImGuiCol.Button, 0, 0, 0, 0);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.04f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.08f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 8f);

        clicked = ImGui.button("##addCard_" + id, cardWidth, cardHeight);

        ImGui.popStyleVar();
        ImGui.popStyleColor(3);

        boolean hovered = ImGui.isItemHovered();
        float alpha = hovered ? 0.5f : 0.25f;

        // Dashed border (simulated with short line segments)
        int borderCol = ImGuiTheme.imCol32(
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, alpha);
        drawDashedRect(draw, x, y, x + cardWidth, y + cardHeight, borderCol, 8f, 6f, 4f);

        // Centered icon + label
        float centerX = x + cardWidth / 2f;
        float centerY = y + cardHeight / 2f;

        float iconW = ImGui.calcTextSize(icon).x;
        float labelW = ImGui.calcTextSize(label).x;
        float lineH = ImGui.getTextLineHeightWithSpacing();

        int iconCol = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, alpha * 1.5f);
        int textCol = ImGuiTheme.imCol32(
                ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, alpha * 2f);

        draw.addText(centerX - iconW / 2f, centerY - lineH - 2, iconCol, icon);
        draw.addText(centerX - labelW / 2f, centerY + 2, textCol, label);

        return clicked;
    }

    // ─── Internal rendering helpers ────────────────────────────────────────

    private void renderHeader(BwuJagexAccount account) {
        String displayName = account.displayLabel().isEmpty()
                ? "Jagex Account" : account.displayLabel();

        ImGui.textColored(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.8f,
                Icons.SHIELD);
        ImGui.sameLine(0, 6);
        ImGui.text(displayName);

        // Subject subtitle (truncated)
        if (!account.subject().isEmpty()) {
            String shortSub = account.subject().length() > 20
                    ? account.subject().substring(0, 20) + "\u2026" : account.subject();
            GuiHelpers.textMuted(shortSub);
        }
    }

    private Action renderCharacters(BwuJagexAccount account, int cardIndex, boolean busy) {
        Action result = null;
        List<BwuJagexCharacter> chars = account.characters();

        if (chars.isEmpty()) {
            GuiHelpers.textMuted("No characters");
            return null;
        }

        int visible = Math.min(chars.size(), MAX_VISIBLE_CHARS);
        for (int i = 0; i < visible; i++) {
            BwuJagexCharacter ch = chars.get(i);
            boolean selected = (i == account.selectedCharacter());

            ImGui.pushID("char_" + cardIndex + "_" + i);

            // Selection indicator + character name as a clickable selectable
            String prefix = selected ? Icons.STAR + "  " : Icons.CIRCLE + "  ";

            if (selected) {
                ImGui.pushStyleColor(ImGuiCol.Text,
                        ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);
            } else {
                ImGui.pushStyleColor(ImGuiCol.Text,
                        ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 1f);
            }

            ImGui.pushStyleColor(ImGuiCol.Header, 0, 0, 0, 0);
            ImGui.pushStyleColor(ImGuiCol.HeaderHovered,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.08f);
            ImGui.pushStyleColor(ImGuiCol.HeaderActive,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.15f);

            if (!busy && ImGui.selectable(prefix + ch.displayName(), selected)) {
                result = new Action(Action.Type.SELECT_CHARACTER, account.uuid(), i);
            }

            ImGui.popStyleColor(4);
            ImGui.popID();
        }

        if (chars.size() > MAX_VISIBLE_CHARS) {
            int remaining = chars.size() - MAX_VISIBLE_CHARS;
            GuiHelpers.textMuted("  +" + remaining + " more");
        }

        return result;
    }

    private void renderSessionLine(BwuJagexAccount account) {
        long now = Instant.now().getEpochSecond();
        long expires = account.sessionExpiresAt();
        boolean hasSession = account.sessionId() != null && !account.sessionId().isEmpty();

        if (!hasSession || expires <= 0) {
            GuiHelpers.statusDot(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B);
            ImGui.sameLine(0, 4);
            GuiHelpers.textMuted("No session");
        } else if (expires < now) {
            GuiHelpers.statusDot(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B);
            ImGui.sameLine(0, 4);
            GuiHelpers.textMuted("Session expired");
        } else if (expires - now < 300) {
            GuiHelpers.statusDot(ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B);
            ImGui.sameLine(0, 4);
            GuiHelpers.textMuted("Expiring soon");
        } else {
            long mins = (expires - now) / 60;
            GuiHelpers.statusDot(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B);
            ImGui.sameLine(0, 4);
            GuiHelpers.textMuted("Active (" + mins + "m)");
        }
    }

    private float estimateHeight(BwuJagexAccount account) {
        float lineH = ImGui.getTextLineHeightWithSpacing();
        float padding = ImGui.getStyle().getWindowPaddingY() * 2;
        float spacing = ImGui.getStyle().getItemSpacingY();

        float headerH = lineH * 2; // name + subject
        int charLines = Math.min(account.characters().size(), MAX_VISIBLE_CHARS);
        if (account.characters().size() > MAX_VISIBLE_CHARS) {
            charLines++;
        }
        float charsH = Math.max(lineH, charLines * lineH); // at least "No characters" line
        float sessionH = lineH;
        float launchH = ImGui.getFrameHeightWithSpacing();
        float actionsH = ImGui.getFrameHeightWithSpacing();

        // 3 separator gaps between sections + bottom margin
        return padding + headerH + charsH + sessionH + launchH + actionsH + spacing * 6;
    }

    // ─── Dashed rectangle ──────────────────────────────────────────────────

    private static void drawDashedRect(ImDrawList draw, float x1, float y1, float x2, float y2,
                                       int color, float rounding, float dashLen, float gapLen) {
        // Simplified: draw a normal rounded rect with reduced alpha
        // (true dashed outlines need per-segment math with pathArcTo,
        //  which is heavy for a subtle decoration — the alpha approach
        //  looks almost identical and is cheaper)
        draw.addRect(x1, y1, x2, y2, color, rounding, 0, 1.5f);
    }

    // ─── Action type ───────────────────────────────────────────────────────

    record Action(Type type, String uuid, int characterIndex) {
        enum Type {
            LAUNCH_JAGEX,
            SELECT_CHARACTER,
            REFRESH_CHARACTERS,
            ENSURE_SESSION,
            REMOVE_JAGEX,
        }
    }
}
