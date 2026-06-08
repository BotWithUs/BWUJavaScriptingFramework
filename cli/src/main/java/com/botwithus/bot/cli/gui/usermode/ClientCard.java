package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.api.ScriptManifest;
import com.botwithus.bot.cli.Connection;
import com.botwithus.bot.cli.gui.CategoryStyle;
import com.botwithus.bot.cli.gui.GuiHelpers;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.core.runtime.ScriptRunner;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiStyle;
import imgui.flag.ImDrawFlags;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;

import java.util.List;

/**
 * Renders a single client connection card in User Mode.
 *
 * The card is hand-drawn (rounded surface, left status stripe, soft corner glow when running)
 * rather than relying on default ImGui chrome. All sizing is derived from the current font
 * size and {@link ImGuiStyle} accessors, so the card scales correctly across DPI changes.
 */
public class ClientCard {

    public ClientCard() {}

    /** Card outer rounding as a fraction of font size. */
    private static final float ROUNDING_EM = 0.5f;
    /** Left status stripe width as a fraction of font size. */
    private static final float STRIPE_EM = 0.22f;
    /** Horizontal interior padding as a fraction of font size. */
    private static final float PAD_X_EM = 0.95f;
    /** Vertical interior padding as a fraction of font size. */
    private static final float PAD_Y_EM = 0.75f;

    public CardAction render(Connection connection, float cardWidth, int cardIndex) {
        boolean alive = connection.isAlive();
        List<ScriptRunner> runners = alive
                ? connection.getRuntime().getRunners()
                : List.of();
        ScriptRunner activeRunner = runners.stream()
                .filter(ScriptRunner::isRunning)
                .findFirst().orElse(null);

        float fontH = ImGui.getFontSize();
        float padX = fontH * PAD_X_EM;
        float padY = fontH * PAD_Y_EM;
        float stripeW = Math.max(2f, fontH * STRIPE_EM);
        float rounding = fontH * ROUNDING_EM;
        float cardHeight = computeCardHeight(padY, alive, activeRunner != null);

        float[] accent = accentColor(alive, activeRunner);

        drawCardBackground(cardWidth, cardHeight, rounding, stripeW, alive, activeRunner, accent);

        return renderInterior(connection, cardIndex, cardWidth, cardHeight,
                padX, padY, stripeW, alive, activeRunner);
    }

    private static float computeCardHeight(float padY, boolean alive, boolean running) {
        ImGuiStyle style = ImGui.getStyle();
        float frameH = ImGui.getFrameHeight();
        float lineH = ImGui.getTextLineHeightWithSpacing();
        float gapY = style.getItemSpacingY();

        // Layout (top to bottom):
        //   padY · header line · spacing · separator · spacing · content · gap · button row · padY
        int contentLines = (alive && running) ? 2 : 1;
        return padY * 2f
                + lineH                  // header
                + gapY * 3f              // spacing + separator gap + spacing
                + lineH * contentLines   // content lines
                + gapY                   // pre-button breathing room
                + frameH;                // button row
    }

    /** Accent palette per state, as {r, g, b}. */
    private static float[] accentColor(boolean alive, ScriptRunner activeRunner) {
        if (!alive) {
            return new float[]{ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B};
        }
        if (activeRunner != null) {
            ScriptManifest manifest = activeRunner.getManifest();
            if (manifest != null) {
                CategoryStyle.Style cs = CategoryStyle.of(manifest.category());
                return new float[]{cs.r(), cs.g(), cs.b()};
            }
            return new float[]{ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B};
        }
        return new float[]{ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B};
    }

    /** Custom-drawn card background on the parent draw list (surface, glow, stripe, border). */
    private void drawCardBackground(float cardWidth, float cardHeight, float rounding, float stripeW,
                                    boolean alive, ScriptRunner activeRunner, float[] accent) {
        float fontH = ImGui.getFontSize();
        float aR = accent[0], aG = accent[1], aB = accent[2];

        float x0 = ImGui.getCursorScreenPosX();
        float y0 = ImGui.getCursorScreenPosY();
        float x1 = x0 + cardWidth;
        float y1 = y0 + cardHeight;
        ImDrawList draw = ImGui.getWindowDrawList();

        int surfaceCol = ImGuiTheme.imCol32(
                ImGuiTheme.SURFACE_R, ImGuiTheme.SURFACE_G, ImGuiTheme.SURFACE_B, 1f);
        int borderCol = ImGuiTheme.imCol32(
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, alive ? 0.6f : 0.35f);
        int stripeCol = ImGuiTheme.imCol32(aR, aG, aB, alive ? 0.95f : 0.7f);

        draw.addRectFilled(x0, y0, x1, y1, surfaceCol, rounding);

        // Subtle corner glow when running — clip to card bounds so the soft edge doesn't bleed
        if (activeRunner != null) {
            draw.pushClipRect(x0, y0, x1, y1, true);
            int glowOuter = ImGuiTheme.imCol32(aR, aG, aB, 0.05f);
            int glowInner = ImGuiTheme.imCol32(aR, aG, aB, 0.10f);
            draw.addCircleFilled(x1 - fontH * 0.5f, y0 + fontH * 0.4f, fontH * 3.5f, glowOuter, 28);
            draw.addCircleFilled(x1 - fontH * 0.5f, y0 + fontH * 0.4f, fontH * 1.8f, glowInner, 24);
            draw.popClipRect();
        }

        // Left accent stripe (rounded on the left side to follow the card corner)
        draw.addRectFilled(x0, y0, x0 + stripeW, y1, stripeCol, rounding, ImDrawFlags.RoundCornersLeft);
        // Outer border
        draw.addRect(x0, y0, x1, y1, borderCol, rounding);
    }

    /** Interior layout via a borderless transparent child; dispatches to the per-state body. */
    private CardAction renderInterior(Connection connection, int cardIndex, float cardWidth, float cardHeight,
                                      float padX, float padY, float stripeW,
                                      boolean alive, ScriptRunner activeRunner) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0f, 0f, 0f, 0f);
        ImGui.pushStyleColor(ImGuiCol.Border, 0f, 0f, 0f, 0f);
        // Reserve space for the stripe on the left; standard padding everywhere else.
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, padX + stripeW * 0.4f, padY);

        if (!alive) {
            ImGui.pushStyleVar(ImGuiStyleVar.Alpha, 0.65f);
        }

        ImGui.beginChild("##clientCard" + cardIndex, cardWidth, cardHeight, false);

        CardAction action;
        if (!alive) {
            action = renderDisconnected(connection, cardIndex);
        } else if (activeRunner != null) {
            action = renderRunning(connection, activeRunner, cardIndex);
        } else {
            action = renderIdle(connection, cardIndex);
        }

        ImGui.endChild();

        if (!alive) {
            ImGui.popStyleVar(); // Alpha
        }
        ImGui.popStyleVar();   // WindowPadding
        ImGui.popStyleColor(2);

        return action;
    }

    // ── Header ─────────────────────────────────────────────────────────

    private void renderHeader(Connection connection, boolean alive, boolean running) {
        if (running) {
            GuiHelpers.pulsingDot(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B);
        } else if (alive) {
            GuiHelpers.statusDot(ImGuiTheme.BLUE_R, ImGuiTheme.BLUE_G, ImGuiTheme.BLUE_B);
        } else {
            GuiHelpers.statusDot(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B);
        }
        ImGui.sameLine(0, ImGui.getStyle().getItemInnerSpacingX());

        String displayName = connection.getAccountName();
        if (displayName == null || displayName.isEmpty()) {
            displayName = connection.getName();
        }
        ImGui.text(displayName);

        String accountName = connection.getAccountName();
        if (accountName != null && !accountName.isEmpty()) {
            ImGui.sameLine(0, ImGui.getStyle().getItemSpacingX());
            GuiHelpers.textMuted(connection.getName());
        }
    }

    // ── State variants ─────────────────────────────────────────────────

    private CardAction renderDisconnected(Connection connection, int cardIndex) {
        renderHeader(connection, false, false);
        ImGui.spacing();
        GuiHelpers.subtleSeparator();
        ImGui.spacing();

        GuiHelpers.textMuted(Icons.PLUG + "  Lost contact");
        ImGui.dummy(0f, ImGui.getStyle().getItemSpacingY() * 0.5f);

        if (GuiHelpers.buttonSecondary(Icons.ROTATE + "  Reconnect##" + cardIndex,
                ImGui.getContentRegionAvailX(), ImGui.getFrameHeight())) {
            return new CardAction(CardAction.Type.RECONNECT, connection, null);
        }
        return null;
    }

    private CardAction renderIdle(Connection connection, int cardIndex) {
        renderHeader(connection, true, false);
        ImGui.spacing();
        GuiHelpers.subtleSeparator();
        ImGui.spacing();

        ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.85f,
                Icons.STOP + "  Pick a script to run");
        ImGui.dummy(0f, ImGui.getStyle().getItemSpacingY() * 0.5f);

        if (GuiHelpers.buttonPrimary(Icons.PLAY + "  Start Script##" + cardIndex,
                ImGui.getContentRegionAvailX(), ImGui.getFrameHeight())) {
            return new CardAction(CardAction.Type.START_SCRIPT, connection, null);
        }
        return null;
    }

    private CardAction renderRunning(Connection connection, ScriptRunner runner, int cardIndex) {
        renderHeader(connection, true, true);
        ImGui.spacing();
        GuiHelpers.subtleSeparator();
        ImGui.spacing();

        renderRunningScript(runner);

        ImGui.dummy(0f, ImGui.getStyle().getItemSpacingY() * 0.5f);

        return renderRunningButtons(connection, runner, cardIndex);
    }

    private void renderRunningScript(ScriptRunner runner) {
        ScriptManifest manifest = runner.getManifest();
        float fontH = ImGui.getFontSize();
        ImGuiStyle style = ImGui.getStyle();

        if (manifest != null) {
            CategoryStyle.Style cs = CategoryStyle.of(manifest.category());
            ImGui.textColored(cs.r(), cs.g(), cs.b(), 1f, cs.icon());
        } else {
            ImGui.textColored(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f,
                    Icons.PLAY);
        }
        ImGui.sameLine(0, style.getItemInnerSpacingX());
        ImGui.text(runner.getScriptName());

        // Right-align an avg-ms metric if we have data
        long loops = runner.getProfiler().getLoopCount();
        long avgMs = loops > 0
                ? runner.getProfiler().getTotalLoopTimeNanos() / loops / 1_000_000L
                : 0;
        if (avgMs > 0) {
            String badge = avgMs + "ms";
            float badgeW = ImGui.calcTextSize(badge).x + fontH * 0.8f; // approximate visual width
            ImGui.sameLine();
            float remaining = ImGui.getContentRegionAvailX();
            if (remaining > badgeW) {
                ImGui.setCursorPosX(ImGui.getCursorPosX() + remaining - badgeW);
            }
            GuiHelpers.statusBadge(badge,
                    ImGuiTheme.BLUE_ACCENT_R, ImGuiTheme.BLUE_ACCENT_G, ImGuiTheme.BLUE_ACCENT_B);
        }

        // Subtitle: author · v1.0
        if (manifest != null) {
            String subtitle = formatSubtitle(manifest);
            if (!subtitle.isEmpty()) {
                GuiHelpers.textMuted(subtitle);
            }
        }
    }

    private static String formatSubtitle(ScriptManifest manifest) {
        StringBuilder sb = new StringBuilder();
        if (!manifest.author().isEmpty()) {
            sb.append("by ").append(manifest.author());
        }
        if (!manifest.version().isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append("v").append(manifest.version());
        }
        return sb.toString();
    }

    private CardAction renderRunningButtons(Connection connection, ScriptRunner runner, int cardIndex) {
        boolean hasUI = runner.getScript().getUI() != null
                || (runner.getConfigFields() != null && !runner.getConfigFields().isEmpty());

        float availW = ImGui.getContentRegionAvailX();
        float btnH = ImGui.getFrameHeight();
        float gap = ImGui.getStyle().getItemSpacingX();

        if (hasUI) {
            float configureW = (availW - gap) * 0.32f;
            float stopW = (availW - gap) - configureW;
            CardAction action = null;
            if (GuiHelpers.buttonSecondary(Icons.GEAR + "  Configure##" + cardIndex, configureW, btnH)) {
                action = new CardAction(CardAction.Type.CONFIGURE, connection, runner);
            }
            ImGui.sameLine(0, gap);
            if (GuiHelpers.buttonDanger(Icons.STOP + "  Stop##" + cardIndex, stopW, btnH)) {
                action = new CardAction(CardAction.Type.STOP, connection, runner);
            }
            return action;
        }

        if (GuiHelpers.buttonDanger(Icons.STOP + "  Stop##" + cardIndex, availW, btnH)) {
            return new CardAction(CardAction.Type.STOP, connection, runner);
        }
        return null;
    }

    /**
     * Represents a user action on a client card.
     */
    public record CardAction(Type type, Connection connection, ScriptRunner runner) {
        public enum Type {
            START_SCRIPT,
            STOP,
            CONFIGURE,
            RECONNECT
        }
    }
}
