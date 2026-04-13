package com.botwithus.bot.cli.gui.loader;

import com.botwithus.bot.cli.gui.GuiHelpers;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.core.loader.BwuClient;
import com.botwithus.bot.core.loader.BwuException;
import com.botwithus.bot.core.loader.BwuStatus;
import com.botwithus.bot.core.loader.BwuUser;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * The loader/launcher screen -- first screen users see on app launch.
 * Handles authentication via bwu.dll (native loader), module updates,
 * and resource loading before the main application starts.
 */
public class LoaderScreen {

    private static final Logger log = LoggerFactory.getLogger(LoaderScreen.class);
    private static final String APP_VERSION = "2.0.0";
    private static final Path TOKEN_PATH = Path.of(System.getProperty("user.home"), ".botwithus", "token.dat");

    // State machine
    private LoaderState state = LoaderState.LOGIN;
    private LoaderState previousState;

    // Animation
    private float contentAlpha = 1f;
    private float launchTimer = 0f;
    private float shakeTimer = 0f;
    private float exitAlpha = 1f;
    private boolean firstFrame = true;

    // Login form — token input for manual token entry
    private final ImString tokenInput = new ImString(512);
    private final ImBoolean rememberMe = new ImBoolean(true);
    private boolean showTokenInput = false;

    // Native auth client (nullable — DLL may not be available)
    private BwuClient bwuClient;
    private CompletableFuture<?> pendingOperation;
    private String authenticatedUsername;
    private boolean dllAvailable;

    // Error state
    private String errorTitle;
    private String errorMessage;
    private LoaderState errorReturnState;

    // Update state
    private String updateStatus = "Checking for updates...";
    private float updateProgress = -1f; // -1 = indeterminate

    // Loading state
    private String loadingStatus = "Initializing...";
    private float loadingProgress = 0f;

    /**
     * Create the loader screen with a BwuClient for native authentication.
     *
     * @param bwuClient the native client, or null if bwu.dll is unavailable
     */
    public LoaderScreen(BwuClient bwuClient) {
        this.bwuClient = bwuClient;
        this.dllAvailable = bwuClient != null;
    }

    /**
     * Returns true when the loader is done and the main app should take over.
     */
    public boolean isComplete() {
        return state == LoaderState.COMPLETE && exitAlpha <= 0f;
    }

    /**
     * Returns the authenticated BwuClient for use by the rest of the application,
     * or null if DLL was unavailable.
     */
    public BwuClient getBwuClient() {
        return bwuClient;
    }

    /**
     * Returns the authenticated username, or null.
     */
    public String getAuthenticatedUsername() {
        return authenticatedUsername;
    }

    /**
     * Render the loader screen. Called every frame from ImGuiApp.
     */
    public void render() {
        float deltaTime = ImGui.getIO().getDeltaTime();
        launchTimer = Math.min(launchTimer + deltaTime, 1f);

        // Handle state transition fade
        updateContentAlpha(deltaTime);

        // Shake animation decay
        if (shakeTimer > 0f) {
            shakeTimer = Math.max(0f, shakeTimer - deltaTime);
        }

        // Exit fade
        if (state == LoaderState.COMPLETE) {
            exitAlpha = Math.max(0f, exitAlpha - deltaTime * 2f);
        }

        // Apply global alpha for exit transition
        if (exitAlpha < 1f) {
            ImGui.pushStyleVar(ImGuiStyleVar.Alpha, exitAlpha);
        }

        LoaderTheme.push();

        float winW = ImGui.getContentRegionAvailX();
        float winH = ImGui.getContentRegionAvailY();

        // Background decoration
        renderBackground(winW, winH);

        // Logo area
        renderLogo(winW, deltaTime);

        ImGui.spacing();
        ImGui.spacing();

        // Content area with fade
        ImGui.pushStyleVar(ImGuiStyleVar.Alpha, contentAlpha * (exitAlpha < 1f ? exitAlpha : 1f));

        switch (state) {
            case LOGIN -> renderLogin(winW);
            case AUTHENTICATING -> renderAuthenticating(winW);
            case UPDATING -> renderUpdating(winW);
            case LOADING -> renderLoading(winW);
            case ERROR -> renderError(winW);
            case COMPLETE -> {} // fading out
        }

        ImGui.popStyleVar(); // content alpha

        // Footer
        renderFooter(winW, winH);

        LoaderTheme.pop();

        if (exitAlpha < 1f) {
            ImGui.popStyleVar(); // global exit alpha
        }

        // Check for auto-login on first frame
        if (firstFrame) {
            firstFrame = false;
            tryAutoLogin();
        }

        // Poll update progress while in UPDATING state
        if (state == LoaderState.UPDATING && bwuClient != null) {
            pollUpdateProgress();
        }
    }

    // --- Background ---

    private void renderBackground(float winW, float winH) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float wx = ImGui.getWindowPosX();
        float wy = ImGui.getWindowPosY();

        // Top accent bar (3px emerald)
        int accentCol = ImGuiTheme.imCol32(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);
        draw.addRectFilled(wx, wy, wx + winW, wy + 3, accentCol);

        // Subtle dot grid
        int dotColor = ImGuiTheme.imCol32(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.03f);
        for (float x = wx; x < wx + winW; x += 40) {
            for (float y = wy; y < wy + winH; y += 40) {
                draw.addCircleFilled(x, y, 1, dotColor, 6);
            }
        }
    }

    // --- Logo ---

    private void renderLogo(float winW, float deltaTime) {
        // Animate: slide down + fade in over 400ms
        float t = Math.min(1f, launchTimer / 0.4f);
        float eased = 1f - (1f - t) * (1f - t); // ease-out quad
        float offsetY = (1f - eased) * -20f;
        float logoAlpha = eased;

        ImGui.pushStyleVar(ImGuiStyleVar.Alpha, logoAlpha);

        ImGui.spacing();
        ImGui.spacing();
        ImGui.spacing();

        // Adjust cursor for slide animation
        float baseY = ImGui.getCursorPosY();
        ImGui.setCursorPosY(baseY + offsetY);

        // "BotWithUs" brand text (centered)
        String brandText = "BotWithUs";
        float textWidth = ImGui.calcTextSize(brandText).x;
        ImGui.setCursorPosX((winW - textWidth) / 2f);

        // Decorative dots flanking the text
        ImDrawList draw = ImGui.getWindowDrawList();
        float textX = ImGui.getCursorScreenPosX();
        float textY = ImGui.getCursorScreenPosY();
        float centerY = textY + ImGui.getTextLineHeight() / 2f;
        int accentCol = ImGuiTheme.imCol32(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, logoAlpha);
        int accentDim = ImGuiTheme.imCol32(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, logoAlpha * 0.3f);

        draw.addCircleFilled(textX - 16, centerY, 3, accentCol, 12);
        draw.addCircleFilled(textX - 28, centerY, 2, accentDim, 12);
        draw.addCircleFilled(textX + textWidth + 16, centerY, 3, accentCol, 12);
        draw.addCircleFilled(textX + textWidth + 28, centerY, 2, accentDim, 12);

        ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, logoAlpha);
        ImGui.text(brandText);
        ImGui.popStyleColor();

        // Version badge
        String version = "v" + APP_VERSION;
        if (dllAvailable) {
            String dllVersion = bwuClient.getVersion();
            if (dllVersion != null && !dllVersion.isEmpty()) {
                version = "v" + dllVersion;
            }
        }
        float versionWidth = ImGui.calcTextSize(version).x;
        ImGui.setCursorPosX((winW - versionWidth) / 2f);
        ImGui.textColored(ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, logoAlpha * 0.6f,
                version);

        ImGui.popStyleVar(); // logoAlpha
    }

    // --- Login ---

    private void renderLogin(float winW) {
        float formWidth = Math.min(320, winW - 48);

        // Scrollable content region so form never gets clipped
        float availH = ImGui.getContentRegionAvailY() - ImGui.getTextLineHeightWithSpacing() - 20f;
        ImGui.beginChild("##loginContent", 0, availH, false);

        float innerW = ImGui.getContentRegionAvailX();
        float innerStartX = (innerW - formWidth) / 2f;

        // Apply shake offset
        float shakeOffset = 0f;
        if (shakeTimer > 0f) {
            shakeOffset = (float) (Math.sin(shakeTimer * 40) * 4 * (shakeTimer / 0.4f));
        }

        if (!dllAvailable) {
            renderNoDllMessage(innerW, formWidth, innerStartX);
        } else if (showTokenInput) {
            renderTokenForm(innerW, formWidth, innerStartX, shakeOffset);
        } else {
            renderSsoForm(innerW, formWidth, innerStartX, shakeOffset);
        }

        ImGui.endChild();
    }

    private void renderSsoForm(float winW, float formWidth, float startX, float shakeOffset) {
        ImGui.spacing();
        ImGui.spacing();

        // Explanation text
        ImGui.setCursorPosX(startX + shakeOffset);
        ImGui.pushTextWrapPos(startX + formWidth);
        GuiHelpers.textSecondary("Click the button below to sign in. Your default browser will open for secure authentication.");
        ImGui.popTextWrapPos();

        ImGui.spacing();
        ImGui.spacing();

        // Remember me
        ImGui.setCursorPosX(startX);
        ImGui.checkbox("Remember session", rememberMe);

        ImGui.spacing();

        // SSO Login button (full width, dark text on emerald bg)
        ImGui.setCursorPosX(startX);
        ImGui.pushStyleColor(ImGuiCol.Text, 0.04f, 0.04f, 0.1f, 1f);
        boolean loginClicked = ImGui.button(Icons.ARROW_RIGHT + "  SIGN IN WITH BROWSER", formWidth, 42);
        ImGui.popStyleColor();

        if (loginClicked) {
            startSsoAuthentication();
        }

        ImGui.spacing();
        ImGui.spacing();

        // Separator with "or" text
        float sepY = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        float wx = ImGui.getWindowPosX();
        String orText = "or connect with token";
        float orWidth = ImGui.calcTextSize(orText).x;
        float midX = wx + winW / 2f;
        int borderCol = ImGuiTheme.imCol32(ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.5f);
        draw.addLine(wx + startX, sepY + 8, midX - orWidth / 2f - 12, sepY + 8, borderCol, 1);
        draw.addLine(midX + orWidth / 2f + 12, sepY + 8, wx + startX + formWidth, sepY + 8, borderCol, 1);
        ImGui.setCursorPosX((winW - orWidth) / 2f);
        GuiHelpers.textMuted(orText);

        ImGui.spacing();

        // Token login button (secondary style)
        ImGui.setCursorPosX(startX);
        if (GuiHelpers.buttonSecondary(Icons.BOLT + "  Token Login##tokenBtn")) {
            showTokenInput = true;
            tokenInput.set("");
        }
    }

    private void renderTokenForm(float winW, float formWidth, float startX, float shakeOffset) {
        ImGui.spacing();

        ImGui.setCursorPosX(startX + shakeOffset);
        ImGui.text(Icons.BOLT + "  Auth Token");

        ImGui.setCursorPosX(startX + shakeOffset);
        ImGui.pushItemWidth(formWidth);
        ImGui.inputTextWithHint("##tokenInput", "Paste your auth token", tokenInput);
        ImGui.popItemWidth();

        ImGui.spacing();

        // Remember me
        ImGui.setCursorPosX(startX);
        ImGui.checkbox("Remember session", rememberMe);

        ImGui.spacing();

        // Login with token button
        ImGui.setCursorPosX(startX);
        ImGui.pushStyleColor(ImGuiCol.Text, 0.04f, 0.04f, 0.1f, 1f);
        boolean tokenLoginClicked = ImGui.button(Icons.ARROW_RIGHT + "  LOGIN WITH TOKEN", formWidth, 38);
        ImGui.popStyleColor();

        if (ImGui.isKeyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) || tokenLoginClicked) {
            String token = tokenInput.get().trim();
            if (!token.isEmpty()) {
                startTokenAuthentication(token);
            }
        }

        ImGui.spacing();

        // Separator
        float sepY = ImGui.getCursorScreenPosY();
        ImDrawList draw = ImGui.getWindowDrawList();
        float wx = ImGui.getWindowPosX();
        String orText = "or sign in with browser";
        float orWidth = ImGui.calcTextSize(orText).x;
        float midX = wx + winW / 2f;
        int borderCol = ImGuiTheme.imCol32(ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.5f);
        draw.addLine(wx + startX, sepY + 8, midX - orWidth / 2f - 12, sepY + 8, borderCol, 1);
        draw.addLine(midX + orWidth / 2f + 12, sepY + 8, wx + startX + formWidth, sepY + 8, borderCol, 1);
        ImGui.setCursorPosX((winW - orWidth) / 2f);
        GuiHelpers.textMuted(orText);

        ImGui.spacing();

        // Back to SSO button
        ImGui.setCursorPosX(startX);
        if (GuiHelpers.buttonSecondary(Icons.NETWORK + "  Sign In With Browser##backBtn")) {
            showTokenInput = false;
        }
    }

    private void renderNoDllMessage(float winW, float formWidth, float startX) {
        ImGui.spacing();
        ImGui.spacing();
        ImGui.spacing();

        // Warning icon
        String icon = Icons.WARNING;
        float iconWidth = ImGui.calcTextSize(icon).x;
        ImGui.setCursorPosX((winW - iconWidth) / 2f);
        ImGui.textColored(ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 1f, icon);

        ImGui.spacing();

        String title = "Loader Not Available";
        float titleWidth = ImGui.calcTextSize(title).x;
        ImGui.setCursorPosX((winW - titleWidth) / 2f);
        ImGui.text(title);

        ImGui.spacing();

        ImGui.setCursorPosX(startX);
        ImGui.pushTextWrapPos(startX + formWidth);
        GuiHelpers.textSecondary(
                "The native loader (bwu.dll) could not be loaded. " +
                "Authentication and module updates are not available. " +
                "The application will start in offline mode.");
        ImGui.popTextWrapPos();

        ImGui.spacing();
        ImGui.spacing();

        // Continue without auth button
        ImGui.setCursorPosX(startX);
        ImGui.pushStyleColor(ImGuiCol.Text, 0.04f, 0.04f, 0.1f, 1f);
        if (ImGui.button(Icons.ARROW_RIGHT + "  CONTINUE OFFLINE", formWidth, 42)) {
            transitionTo(LoaderState.LOADING);
            startLoading();
        }
        ImGui.popStyleColor();
    }

    // --- Authenticating ---

    private void renderAuthenticating(float winW) {
        ImGui.spacing();
        ImGui.spacing();
        ImGui.spacing();

        // Spinner
        renderSpinner(winW);

        ImGui.spacing();

        // Pulsing text
        float pulse = 0.6f + 0.4f * (float) Math.sin(ImGui.getTime() * 3.0);
        String status = "Signing in...";
        if (!showTokenInput) {
            status = "Waiting for browser sign-in...";
        }
        float statusWidth = ImGui.calcTextSize(status).x;
        ImGui.setCursorPosX((winW - statusWidth) / 2f);
        ImGui.textColored(ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, pulse, status);

        ImGui.spacing();
        ImGui.spacing();
        ImGui.spacing();

        // Cancel button
        float cancelWidth = 100;
        ImGui.setCursorPosX((winW - cancelWidth) / 2f);
        ImGui.pushStyleColor(ImGuiCol.Button, 0f, 0f, 0f, 0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.05f);
        ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f);
        if (ImGui.button("Cancel", cancelWidth, 0)) {
            if (pendingOperation != null) {
                pendingOperation.cancel(true);
            }
            transitionTo(LoaderState.LOGIN);
        }
        ImGui.popStyleColor(3);

        // Check if auth completed
        if (pendingOperation != null && pendingOperation.isDone()) {
            pendingOperation = null;
        }
    }

    // --- Updating ---

    private void renderUpdating(float winW) {
        float contentWidth = Math.min(350, winW - 48);
        float startX = (winW - contentWidth) / 2f;

        ImGui.spacing();
        ImGui.spacing();

        // Welcome text
        String welcome = "Welcome back, " + (authenticatedUsername != null ? authenticatedUsername : "User") + "!";
        float welcomeWidth = ImGui.calcTextSize(welcome).x;
        ImGui.setCursorPosX((winW - welcomeWidth) / 2f);
        ImGui.text(welcome);

        ImGui.spacing();
        ImGui.spacing();
        ImGui.spacing();

        // Status text
        float statusWidth = ImGui.calcTextSize(updateStatus).x;
        ImGui.setCursorPosX((winW - statusWidth) / 2f);
        GuiHelpers.textSecondary(updateStatus);

        ImGui.spacing();

        // Progress bar
        ImGui.setCursorPosX(startX);
        if (updateProgress < 0) {
            // Indeterminate -- sliding segment
            renderIndeterminateProgress(startX, contentWidth);
        } else {
            ImGui.pushStyleColor(ImGuiCol.PlotHistogram,
                    ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.8f);
            ImGui.pushStyleColor(ImGuiCol.FrameBg,
                    ImGuiTheme.INPUT_BG_R, ImGuiTheme.INPUT_BG_G, ImGuiTheme.INPUT_BG_B, 1f);
            ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 4);
            ImGui.progressBar(updateProgress, contentWidth, 8);
            ImGui.popStyleVar();
            ImGui.popStyleColor(2);
        }
    }

    // --- Loading ---

    private void renderLoading(float winW) {
        float contentWidth = Math.min(350, winW - 48);
        float startX = (winW - contentWidth) / 2f;

        ImGui.spacing();
        ImGui.spacing();
        ImGui.spacing();

        // Loading icon
        String icon = Icons.CUBE;
        float iconWidth = ImGui.calcTextSize(icon).x;
        ImGui.setCursorPosX((winW - iconWidth) / 2f);
        ImGui.textColored(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.6f, icon);

        ImGui.spacing();
        ImGui.spacing();

        // Status text
        float statusWidth = ImGui.calcTextSize(loadingStatus).x;
        ImGui.setCursorPosX((winW - statusWidth) / 2f);
        GuiHelpers.textSecondary(loadingStatus);

        ImGui.spacing();

        // Progress bar
        ImGui.setCursorPosX(startX);
        ImGui.pushStyleColor(ImGuiCol.PlotHistogram,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.8f);
        ImGui.pushStyleColor(ImGuiCol.FrameBg,
                ImGuiTheme.INPUT_BG_R, ImGuiTheme.INPUT_BG_G, ImGuiTheme.INPUT_BG_B, 1f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 4);
        ImGui.progressBar(loadingProgress, contentWidth, 8);
        ImGui.popStyleVar();
        ImGui.popStyleColor(2);
    }

    // --- Error ---

    private void renderError(float winW) {
        float contentWidth = Math.min(320, winW - 48);
        float startX = (winW - contentWidth) / 2f;

        ImGui.spacing();
        ImGui.spacing();
        ImGui.spacing();

        // Error icon
        String icon = Icons.WARNING;
        float iconWidth = ImGui.calcTextSize(icon).x;
        ImGui.setCursorPosX((winW - iconWidth) / 2f);
        ImGui.textColored(ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f, icon);

        ImGui.spacing();
        ImGui.spacing();

        // Error title
        if (errorTitle != null) {
            float titleWidth = ImGui.calcTextSize(errorTitle).x;
            ImGui.setCursorPosX((winW - titleWidth) / 2f);
            ImGui.text(errorTitle);
        }

        ImGui.spacing();

        // Error message
        if (errorMessage != null) {
            ImGui.setCursorPosX(startX);
            ImGui.pushTextWrapPos(startX + contentWidth);
            GuiHelpers.textSecondary(errorMessage);
            ImGui.popTextWrapPos();
        }

        ImGui.spacing();
        ImGui.spacing();
        ImGui.spacing();

        // Try Again button
        ImGui.setCursorPosX(startX);
        ImGui.pushStyleColor(ImGuiCol.Text, 0.04f, 0.04f, 0.1f, 1f);
        if (ImGui.button(Icons.REDO + "  TRY AGAIN", contentWidth, 42)) {
            transitionTo(errorReturnState != null ? errorReturnState : LoaderState.LOGIN);
        }
        ImGui.popStyleColor();
    }

    // --- Footer ---

    private void renderFooter(float winW, float winH) {
        float footerY = winH - ImGui.getTextLineHeightWithSpacing() - 10;
        if (footerY > ImGui.getCursorPosY()) {
            ImGui.setCursorPosY(footerY);
            GuiHelpers.subtleSeparator();
            ImGui.spacing();

            ImGui.setCursorPosX(12);
            GuiHelpers.textMuted("v" + APP_VERSION);

            ImGui.sameLine(winW - ImGui.calcTextSize("Discord | Website").x - 20);
            ImGui.pushStyleColor(ImGuiCol.Button, 0, 0, 0, 0);
            ImGui.pushStyleColor(ImGuiCol.Text,
                    ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 1f);
            ImGui.smallButton("Discord");
            ImGui.sameLine(0, 4);
            GuiHelpers.textMuted("|");
            ImGui.sameLine(0, 4);
            ImGui.smallButton("Website");
            ImGui.popStyleColor(2);
        }
    }

    // --- Helpers ---

    private void renderSpinner(float winW) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float cx = ImGui.getWindowPosX() + winW / 2f;
        float cy = ImGui.getCursorScreenPosY() + 20;
        float radius = 14;

        // Track circle
        int trackCol = ImGuiTheme.imCol32(ImGuiTheme.INPUT_BG_R, ImGuiTheme.INPUT_BG_G, ImGuiTheme.INPUT_BG_B, 1f);
        draw.addCircle(cx, cy, radius, trackCol, 32, 2f);

        // Active arc
        float angle = (float) (ImGui.getTime() * 4.0);
        int arcCol = ImGuiTheme.imCol32(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f);
        int segments = 20;
        float arcLength = (float) (Math.PI * 1.5);
        for (int i = 0; i < segments; i++) {
            float a1 = angle + arcLength * i / segments;
            float a2 = angle + arcLength * (i + 1) / segments;
            float x1 = cx + radius * (float) Math.cos(a1);
            float y1 = cy + radius * (float) Math.sin(a1);
            float x2 = cx + radius * (float) Math.cos(a2);
            float y2 = cy + radius * (float) Math.sin(a2);
            draw.addLine(x1, y1, x2, y2, arcCol, 2.5f);
        }

        ImGui.dummy(0, 40);
    }

    private void renderIndeterminateProgress(float startX, float width) {
        ImDrawList draw = ImGui.getWindowDrawList();
        float barY = ImGui.getCursorScreenPosY();
        float barX = ImGui.getWindowPosX() + startX;
        float barH = 8;

        // Track
        int trackCol = ImGuiTheme.imCol32(ImGuiTheme.INPUT_BG_R, ImGuiTheme.INPUT_BG_G, ImGuiTheme.INPUT_BG_B, 1f);
        draw.addRectFilled(barX, barY, barX + width, barY + barH, trackCol, 4);

        // Sliding segment
        float t = (float) (Math.sin(ImGui.getTime() * 2.0) * 0.5 + 0.5);
        float segWidth = width * 0.3f;
        float segX = barX + t * (width - segWidth);
        int segCol = ImGuiTheme.imCol32(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.8f);
        draw.addRectFilled(segX, barY, segX + segWidth, barY + barH, segCol, 4);

        ImGui.dummy(0, barH + 4);
    }

    private void transitionTo(LoaderState newState) {
        previousState = state;
        state = newState;
        contentAlpha = 0f; // triggers fade-in
    }

    private void updateContentAlpha(float deltaTime) {
        if (contentAlpha < 1f) {
            contentAlpha = Math.min(1f, contentAlpha + deltaTime / 0.15f);
        }
    }

    // --- Auth / Update / Loading Logic (via BwuClient) ---

    private void tryAutoLogin() {
        if (!dllAvailable) return;

        // Try loading a saved token from disk
        try {
            if (Files.exists(TOKEN_PATH)) {
                bwuClient.loadToken(TOKEN_PATH);
                if (bwuClient.isLoggedIn()) {
                    BwuUser user = bwuClient.getUser();
                    authenticatedUsername = user.name();
                    log.info("Auto-login successful for {}", authenticatedUsername);
                    transitionTo(LoaderState.UPDATING);
                    startModuleRefresh();
                    return;
                }
            }
        } catch (BwuException e) {
            log.debug("Auto-login with saved token failed: {}", e.getMessage());
        }
        // No saved token or token invalid — stay on LOGIN screen
    }

    private void startSsoAuthentication() {
        transitionTo(LoaderState.AUTHENTICATING);
        pendingOperation = CompletableFuture.runAsync(() -> {
            try {
                // bwu.login() opens browser and blocks until callback or timeout (5 min)
                bwuClient.login();

                if (bwuClient.isLoggedIn()) {
                    BwuUser user = bwuClient.getUser();
                    authenticatedUsername = user.name();
                    log.info("SSO login successful for {}", authenticatedUsername);

                    if (rememberMe.get()) {
                        saveToken();
                    }

                    transitionTo(LoaderState.UPDATING);
                    startModuleRefresh();
                } else {
                    errorTitle = "Authentication Failed";
                    errorMessage = "Browser sign-in did not complete. Please try again.";
                    errorReturnState = LoaderState.LOGIN;
                    shakeTimer = 0.4f;
                    transitionTo(LoaderState.ERROR);
                }
            } catch (BwuException e) {
                log.error("SSO login error: {}", e.getMessage());
                errorTitle = "Authentication Failed";
                errorMessage = e.getMessage();
                errorReturnState = LoaderState.LOGIN;
                shakeTimer = 0.4f;
                transitionTo(LoaderState.ERROR);
            }
        });
    }

    private void startTokenAuthentication(String token) {
        transitionTo(LoaderState.AUTHENTICATING);
        pendingOperation = CompletableFuture.runAsync(() -> {
            try {
                bwuClient.loginWithToken(token);

                if (bwuClient.isLoggedIn()) {
                    BwuUser user = bwuClient.getUser();
                    authenticatedUsername = user.name();
                    log.info("Token login successful for {}", authenticatedUsername);

                    if (rememberMe.get()) {
                        saveToken();
                    }

                    transitionTo(LoaderState.UPDATING);
                    startModuleRefresh();
                } else {
                    errorTitle = "Authentication Failed";
                    errorMessage = "The token is invalid or expired. Please try again.";
                    errorReturnState = LoaderState.LOGIN;
                    shakeTimer = 0.4f;
                    transitionTo(LoaderState.ERROR);
                }
            } catch (BwuException e) {
                log.error("Token login error: {}", e.getMessage());
                errorTitle = "Authentication Failed";
                errorMessage = e.getMessage();
                errorReturnState = LoaderState.LOGIN;
                shakeTimer = 0.4f;
                transitionTo(LoaderState.ERROR);
            }
        });
    }

    private void saveToken() {
        try {
            Files.createDirectories(TOKEN_PATH.getParent());
            bwuClient.saveToken(TOKEN_PATH);
            log.debug("Token saved to {}", TOKEN_PATH);
        } catch (Exception e) {
            log.warn("Failed to save auth token: {}", e.getMessage());
        }
    }

    private void startModuleRefresh() {
        updateStatus = "Checking for updates...";
        updateProgress = -1f;

        try {
            // Triggers a background download if a newer module is available
            bwuClient.refreshModule();
        } catch (BwuException e) {
            log.warn("Module refresh failed, proceeding anyway: {}", e.getMessage());
            updateStatus = "Skipping update check.";
            updateProgress = 1.0f;
        }
        // Progress is polled from render() via pollUpdateProgress()
    }

    /**
     * Called every frame while in UPDATING state. Reads BwuStatus to track
     * download progress and transitions to LOADING when the module is ready.
     */
    private void pollUpdateProgress() {
        try {
            BwuStatus status = bwuClient.getStatus();

            if (status.downloading()) {
                updateStatus = "Downloading module... " + status.downloadProgress() + "%";
                updateProgress = status.downloadProgress() / 100f;
            } else if (status.moduleReady()) {
                updateStatus = "Up to date.";
                updateProgress = 1.0f;
                transitionTo(LoaderState.LOADING);
                startLoading();
            } else if (status.downloadProgress() == 0 && !status.downloading()) {
                // Not downloading, not ready yet — may still be checking
                // If we just triggered refreshModule(), give it a moment
                updateStatus = "Checking for updates...";
                updateProgress = -1f;

                // If the module is already present locally (no download needed),
                // moduleReady will be true on the next poll. But if no module
                // exists and the server didn't trigger a download, move on.
                // We detect this by checking if we've been waiting too long
                // with no download starting — the DLL handles this internally.
            }
        } catch (BwuException e) {
            log.warn("Status poll error, proceeding: {}", e.getMessage());
            transitionTo(LoaderState.LOADING);
            startLoading();
        }
    }

    /**
     * Perform real application loading -- verify the environment and transition.
     */
    private void startLoading() {
        loadingStatus = "Preparing environment...";
        loadingProgress = 0f;

        pendingOperation = CompletableFuture.runAsync(() -> {
            // Verify scripts directory exists
            loadingStatus = "Checking scripts directory...";
            loadingProgress = 0.3f;
            Path scriptsDir = Path.of("scripts");
            if (!Files.isDirectory(scriptsDir)) {
                try {
                    Files.createDirectories(scriptsDir);
                } catch (java.io.IOException e) {
                    log.warn("Could not create scripts directory: {}", e.getMessage());
                }
            }

            // Verify config directory exists
            loadingStatus = "Checking configuration...";
            loadingProgress = 0.6f;
            Path configDir = Path.of(System.getProperty("user.home"), ".botwithus");
            if (!Files.isDirectory(configDir)) {
                try {
                    Files.createDirectories(configDir);
                } catch (java.io.IOException e) {
                    log.warn("Could not create config directory: {}", e.getMessage());
                }
            }

            loadingStatus = "Ready!";
            loadingProgress = 1.0f;
            transitionTo(LoaderState.COMPLETE);
        }).exceptionally(ex -> {
            log.error("Loading failed: {}", ex.getMessage());
            errorTitle = "Loading Failed";
            errorMessage = "Could not initialize the application: " + ex.getMessage();
            errorReturnState = LoaderState.LOADING;
            transitionTo(LoaderState.ERROR);
            return null;
        });
    }
}
