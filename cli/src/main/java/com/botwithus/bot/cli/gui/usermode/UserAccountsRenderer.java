package com.botwithus.bot.cli.gui.usermode;

import com.botwithus.bot.cli.gui.GuiHelpers;
import com.botwithus.bot.cli.gui.Icons;
import com.botwithus.bot.cli.gui.ImGuiTheme;
import com.botwithus.bot.core.loader.BwuAccount;
import com.botwithus.bot.core.loader.BwuAccountType;
import com.botwithus.bot.core.loader.BwuClient;
import com.botwithus.bot.core.loader.BwuException;
import com.botwithus.bot.core.loader.BwuJagexAccount;
import com.botwithus.bot.core.loader.BwuTargetType;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Renders the account launcher section in the Launcher tab.
 * Shows Jagex account cards in a responsive grid with character
 * selection and one-click launch, plus compact classic account
 * management.
 */
public class UserAccountsRenderer {

    private static final Logger log = LoggerFactory.getLogger(UserAccountsRenderer.class);

    private static final float CARD_SPACING = 10f;

    private final AccountCard accountCard = new AccountCard();

    private BwuClient bwu;
    private ExecutorService executor;

    // Account data (refreshed from DLL)
    private List<BwuJagexAccount> jagexAccounts = List.of();
    private List<BwuAccount> classicAccounts = List.of();
    private long lastRefresh;

    // Async state
    private CompletableFuture<?> pendingOp;
    private String pendingLabel;

    // Status toast
    private String toastMessage;
    private float toastTimer;
    private boolean toastIsError;

    // Confirm delete
    private String confirmDeleteUuid;

    // Classic account add popup
    private boolean showClassicPopup;
    private final ImString addName = new ImString(256);
    private final ImString addPassword = new ImString(512);
    private final ImString addPin = new ImString(64);
    private final ImInt addWorldA = new ImInt(1);
    private final ImBoolean addAutoLogin = new ImBoolean(false);

    public void setBwuClient(BwuClient bwu) {
        this.bwu = bwu;
    }

    public void setExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    /**
     * Render the accounts launcher section. Returns the height consumed
     * so the caller can lay out content below.
     */
    public void render() {
        if (bwu == null) {
            return; // silently skip if no DLL
        }

        float dt = ImGui.getIO().getDeltaTime();

        // Decay toast
        if (toastTimer > 0) {
            toastTimer -= dt;
            if (toastTimer <= 0) {
                toastMessage = null;
            }
        }

        // Clear finished ops
        if (pendingOp != null && pendingOp.isDone()) {
            pendingOp = null;
            pendingLabel = null;
        }

        // Refresh data every 2s
        long now = System.currentTimeMillis();
        if (now - lastRefresh > 2000) {
            refreshData();
            lastRefresh = now;
        }

        boolean busy = pendingOp != null;
        boolean hasAccounts = !jagexAccounts.isEmpty() || !classicAccounts.isEmpty();

        // ── Section header ──
        renderSectionHeader(busy);

        // ── Toast ──
        if (toastMessage != null) {
            renderToast();
        }

        // ── Pending spinner ──
        if (busy) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(ImGui.getTime() * 4.0);
            String label = pendingLabel != null ? pendingLabel : "Working...";
            ImGui.textColored(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, pulse,
                    Icons.SPINNER + "  " + label);
            ImGui.spacing();
        }

        // ── Account card grid ──
        renderCardGrid(busy);

        ImGui.spacing();
        ImGui.spacing();

        // ── Compact classic accounts bar ──
        renderClassicBar(busy);

        ImGui.spacing();
        GuiHelpers.subtleSeparator();
        ImGui.spacing();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Section header
    // ══════════��═════════════════════════════════��══════════════════════════

    private void renderSectionHeader(boolean busy) {
        // Accent bar + "Accounts" label
        ImDrawList draw = ImGui.getWindowDrawList();
        float cursorX = ImGui.getCursorScreenPosX();
        float cursorY = ImGui.getCursorScreenPosY();
        float textH = ImGui.getTextLineHeight();

        int accentCol = ImGuiTheme.imCol32(
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.8f);
        draw.addRectFilled(cursorX, cursorY, cursorX + 3f, cursorY + textH, accentCol, 2f);

        ImGui.setCursorPosX(ImGui.getCursorPosX() + ImGui.getStyle().getIndentSpacing() * 0.5f);
        ImGui.textColored(ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.9f,
                "Accounts");

        // Right-aligned restore button
        float restoreBtnW = ImGui.calcTextSize(Icons.ROTATE + "  Restore").x
                + ImGui.getStyle().getFramePaddingX() * 2;
        ImGui.sameLine(ImGui.getContentRegionAvailX() - restoreBtnW);
        if (busy) ImGui.beginDisabled();
        if (ImGui.smallButton(Icons.ROTATE + "  Restore")) {
            startRestore();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Restore Jagex accounts from previous session");
        if (busy) ImGui.endDisabled();

        ImGui.spacing();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Toast notification
    // ���════════════════���═══════════════════════════════���═════════════════════

    private void renderToast() {
        float alpha = Math.min(1f, toastTimer / 0.3f);
        float r = toastIsError ? ImGuiTheme.RED_R : ImGuiTheme.ACCENT_R;
        float g = toastIsError ? ImGuiTheme.RED_G : ImGuiTheme.ACCENT_G;
        float b = toastIsError ? ImGuiTheme.RED_B : ImGuiTheme.ACCENT_B;

        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = ImGui.getContentRegionAvailX();
        float h = ImGui.getTextLineHeightWithSpacing() + 6;

        draw.addRectFilled(x, y, x + w, y + h,
                ImGuiTheme.imCol32(r, g, b, 0.10f * alpha), 4f);
        draw.addRectFilled(x, y, x + 3, y + h,
                ImGuiTheme.imCol32(r, g, b, 0.7f * alpha), 2f);

        ImGui.setCursorPosX(ImGui.getCursorPosX() + ImGui.getStyle().getIndentSpacing() * 0.5f);
        ImGui.pushStyleVar(ImGuiStyleVar.Alpha, alpha);
        ImGui.textColored(r, g, b, alpha,
                (toastIsError ? Icons.WARNING : Icons.CHECK) + "  " + toastMessage);
        ImGui.popStyleVar();
        ImGui.dummy(0, 2);
    }

    // ═���═════════════════════════════���═══════════════════════════════════════
    //  Jagex account card grid
    // ══════════════���════════════════════════════════���═══════════════════════

    private void renderCardGrid(boolean busy) {
        float availW = ImGui.getContentRegionAvailX();

        // Total items = accounts + 1 add-card
        int totalItems = jagexAccounts.size() + 1;

        // Decide columns: fit as many as the space allows, but each card
        // needs at least 280px to show character names without truncation.
        int columns = Math.max(1, (int) ((availW + CARD_SPACING) / (280 + CARD_SPACING)));
        // Never more columns than items — avoids tiny cards when there's just 1 account
        columns = Math.min(columns, totalItems);
        float cardWidth = (availW - (columns - 1) * CARD_SPACING) / columns;

        // Minimum height for the add card
        float addCardH = ImGui.getTextLineHeightWithSpacing() * 4;

        int col = 0;
        for (int i = 0; i < jagexAccounts.size(); i++) {
            if (col > 0) ImGui.sameLine(0, CARD_SPACING);

            AccountCard.Action action = accountCard.render(jagexAccounts.get(i), cardWidth, i, busy);
            if (action != null) handleAction(action);

            col++;
            if (col >= columns) {
                col = 0;
                ImGui.spacing();
            }
        }

        // "Add Jagex Account" card
        if (col > 0) ImGui.sameLine(0, CARD_SPACING);
        if (busy) ImGui.beginDisabled();

        if (accountCard.renderAddCard("Add Jagex\nAccount", Icons.PLUS, cardWidth, addCardH, "jagex")) {
            startJagexLogin();
        }

        if (busy) ImGui.endDisabled();
    }

    // ══════════════════════════��═════════════════════════════��══════════════
    //  Classic accounts compact bar
    // ════════════════��══════════════════════════════════════════════════════

    private void renderClassicBar(boolean busy) {
        if (classicAccounts.isEmpty() && !showClassicPopup) {
            // Just a small "add classic account" link
            if (busy) ImGui.beginDisabled();
            ImGui.pushStyleColor(ImGuiCol.Button, 0, 0, 0, 0);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                    ImGuiTheme.TEXT_R, ImGuiTheme.TEXT_G, ImGuiTheme.TEXT_B, 0.05f);
            ImGui.pushStyleColor(ImGuiCol.Text,
                    ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B, 0.8f);
            if (ImGui.smallButton(Icons.PLUS + "  Add classic account (for injection credentials)")) {
                showClassicPopup = true;
                resetAddForm();
            }
            ImGui.popStyleColor(3);
            if (busy) ImGui.endDisabled();
            return;
        }

        // Compact row: "Classic: acct1, acct2 [+]"
        GuiHelpers.textMuted("Classic:");

        for (int i = 0; i < classicAccounts.size(); i++) {
            ImGui.sameLine(0, 6);
            BwuAccount acct = classicAccounts.get(i);

            // Chip-style badge for each classic account
            renderClassicChip(acct, i, busy);
        }

        ImGui.sameLine(0, 8);
        if (busy) ImGui.beginDisabled();
        ImGui.pushStyleColor(ImGuiCol.Button, 0, 0, 0, 0);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.1f);
        ImGui.pushStyleColor(ImGuiCol.Text,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.8f);
        if (ImGui.smallButton(Icons.PLUS + "##addClassic")) {
            showClassicPopup = true;
            resetAddForm();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Add classic account");
        ImGui.popStyleColor(3);
        if (busy) ImGui.endDisabled();

        // Add classic account popup
        if (showClassicPopup) {
            renderClassicAddPopup(busy);
        }
    }

    private void renderClassicChip(BwuAccount acct, int index, boolean busy) {
        ImDrawList draw = ImGui.getWindowDrawList();
        String label = acct.name();
        if (label.length() > 18) label = label.substring(0, 15) + "\u2026";

        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float textW = ImGui.calcTextSize(label).x;
        float textH = ImGui.calcTextSize(label).y;
        float padX = 8f;
        float padY = 2f;
        float chipW = textW + padX * 2 + 16; // extra for remove button
        float chipH = textH + padY * 2;

        // Chip background
        int bgCol = ImGuiTheme.imCol32(
                ImGuiTheme.ELEVATED_R, ImGuiTheme.ELEVATED_G, ImGuiTheme.ELEVATED_B, 1f);
        int borderCol = ImGuiTheme.imCol32(
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.3f);
        draw.addRectFilled(x, y, x + chipW, y + chipH, bgCol, 4f);
        draw.addRect(x, y, x + chipW, y + chipH, borderCol, 4f);

        // World indicator dot
        int dotCol = ImGuiTheme.imCol32(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.5f);
        draw.addCircleFilled(x + padX, y + chipH / 2f, 2.5f, dotCol, 8);

        // Label text
        int textCol = ImGuiTheme.imCol32(ImGuiTheme.TEXT_SEC_R, ImGuiTheme.TEXT_SEC_G, ImGuiTheme.TEXT_SEC_B, 1f);
        draw.addText(x + padX + 8, y + padY, textCol, label);

        ImGui.dummy(chipW, chipH);

        if (ImGui.isItemHovered()) {
            String tooltip = acct.name() + " | W" + acct.worldA()
                    + " | " + acct.accountType().name().toLowerCase();
            ImGui.setTooltip(tooltip);
        }

        // Right-click to remove
        if (ImGui.isItemHovered() && ImGui.isMouseClicked(1)) {
            if (!busy) {
                try {
                    bwu.removeAccount(acct.uuid());
                    refreshData();
                    toast("Account removed", false);
                } catch (BwuException e) {
                    toast("Remove failed: " + e.getMessage(), true);
                }
            }
        }
    }

    private void renderClassicAddPopup(boolean busy) {
        ImGui.spacing();

        ImGui.pushStyleColor(ImGuiCol.ChildBg,
                ImGuiTheme.SURFACE_R, ImGuiTheme.SURFACE_G, ImGuiTheme.SURFACE_B, 1f);
        ImGui.pushStyleColor(ImGuiCol.Border,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.2f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 6f);

        float popupW = ImGui.getContentRegionAvailX() * 0.6f;
        ImGui.beginChild("##classicAddPopup", popupW, 0, true);
        ImGui.popStyleColor(2);
        ImGui.popStyleVar();

        ImGui.text(Icons.PLUS + "  New Classic Account");
        ImGui.spacing();
        GuiHelpers.subtleSeparator();
        ImGui.spacing();

        float fieldW = ImGui.getContentRegionAvailX();

        GuiHelpers.textSecondary("Email / Username");
        ImGui.pushItemWidth(fieldW);
        ImGui.inputText("##addClassicName", addName);
        ImGui.popItemWidth();

        ImGui.spacing();

        GuiHelpers.textSecondary("Password");
        ImGui.pushItemWidth(fieldW);
        ImGui.inputText("##addClassicPass", addPassword, ImGuiInputTextFlags.Password);
        ImGui.popItemWidth();

        ImGui.spacing();

        GuiHelpers.textSecondary("Bank PIN");
        ImGui.pushItemWidth(ImGui.getFrameHeight() * 3);
        ImGui.inputText("##addClassicPin", addPin);
        ImGui.popItemWidth();
        ImGui.sameLine(0, 16);
        GuiHelpers.textSecondary("World");
        ImGui.sameLine(0, 6);
        ImGui.pushItemWidth(ImGui.getFrameHeight() * 3);
        ImGui.inputInt("##addClassicWorld", addWorldA, 0);
        ImGui.popItemWidth();
        ImGui.sameLine(0, 16);
        ImGui.checkbox("Auto-login", addAutoLogin);

        ImGui.spacing();
        ImGui.spacing();

        if (busy) ImGui.beginDisabled();

        // Add button
        ImGui.pushStyleColor(ImGuiCol.Text, 0.04f, 0.04f, 0.1f, 1f);
        ImGui.pushStyleColor(ImGuiCol.Button,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.85f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                ImGuiTheme.ACCENT_R * 0.85f, ImGuiTheme.ACCENT_G * 0.85f, ImGuiTheme.ACCENT_B * 0.85f, 1f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                ImGuiTheme.ACCENT_R * 0.7f, ImGuiTheme.ACCENT_G * 0.7f, ImGuiTheme.ACCENT_B * 0.7f, 1f);
        if (ImGui.button(Icons.CHECK + "  Add")) {
            addClassicAccount();
        }
        ImGui.popStyleColor(4);

        ImGui.sameLine(0, 8);
        if (GuiHelpers.buttonSecondary("Cancel")) {
            showClassicPopup = false;
        }

        if (busy) ImGui.endDisabled();

        ImGui.endChild();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Action handling
    // ══════════��════════════════════════════════════���═══════════════════════

    private void handleAction(AccountCard.Action action) {
        switch (action.type()) {
            case LAUNCH_JAGEX -> launchJagex(action.uuid(), action.characterIndex());
            case SELECT_CHARACTER -> selectCharacter(action.uuid(), action.characterIndex());
            case REFRESH_CHARACTERS -> refreshCharacters(action.uuid());
            case ENSURE_SESSION -> ensureSession(action.uuid());
            case REMOVE_JAGEX -> removeJagex(action.uuid());
        }
    }

    private void launchJagex(String jagexUuid, int charIndex) {
        pendingLabel = "Launching...";
        pendingOp = CompletableFuture.runAsync(() -> {
            try {
                bwu.jagexEnsureSession(jagexUuid);
                // Classic account UUID is not used for Jagex launches — the DLL
                // passes session credentials (JX_SESSION_ID, etc.) via env vars.
                String bwuUuid = classicAccounts.isEmpty() ? "" : classicAccounts.getFirst().uuid();
                bwu.jagexLaunch(jagexUuid, bwuUuid, charIndex);
                toast("Game client launching", false);
            } catch (BwuException e) {
                log.error("Launch failed: {}", e.getMessage());
                toast("Launch failed: " + e.getMessage(), true);
            }
        }, executor);
    }

    private void selectCharacter(String uuid, int index) {
        try {
            bwu.jagexSelectCharacter(uuid, index);
            refreshData();
        } catch (BwuException e) {
            toast("Select failed: " + e.getMessage(), true);
        }
    }

    private void refreshCharacters(String uuid) {
        pendingLabel = "Refreshing characters...";
        pendingOp = CompletableFuture.runAsync(() -> {
            try {
                bwu.jagexEnsureSession(uuid);
                bwu.jagexRefreshCharacters(uuid);
                refreshData();
                toast("Characters refreshed", false);
            } catch (BwuException e) {
                log.error("Refresh failed: {}", e.getMessage());
                toast("Refresh failed: " + e.getMessage(), true);
            }
        }, executor);
    }

    private void ensureSession(String uuid) {
        pendingLabel = "Refreshing session...";
        pendingOp = CompletableFuture.runAsync(() -> {
            try {
                bwu.jagexEnsureSession(uuid);
                refreshData();
                toast("Session refreshed", false);
            } catch (BwuException e) {
                log.error("Session refresh failed: {}", e.getMessage());
                toast("Session failed: " + e.getMessage(), true);
            }
        }, executor);
    }

    private void removeJagex(String uuid) {
        try {
            bwu.jagexRemoveAccount(uuid);
            refreshData();
            toast("Account removed", false);
        } catch (BwuException e) {
            toast("Remove failed: " + e.getMessage(), true);
        }
    }

    private void startJagexLogin() {
        pendingLabel = "Waiting for Jagex sign-in (browser)...";
        pendingOp = CompletableFuture.runAsync(() -> {
            try {
                BwuJagexAccount acct = bwu.jagexLogin();
                refreshData();
                toast("Added: " + acct.displayLabel(), false);
            } catch (BwuException e) {
                log.error("Jagex login failed: {}", e.getMessage());
                toast("Login failed: " + e.getMessage(), true);
            }
        }, executor);
    }

    private void startRestore() {
        pendingLabel = "Restoring accounts...";
        pendingOp = CompletableFuture.runAsync(() -> {
            try {
                bwu.jagexRestoreAccounts();
                refreshData();
                int count = bwu.jagexAccountCount();
                toast("Restored " + count + " account(s)", false);
            } catch (BwuException e) {
                log.error("Restore failed: {}", e.getMessage());
                toast("Restore failed: " + e.getMessage(), true);
            }
        }, executor);
    }

    private void addClassicAccount() {
        String name = addName.get().trim();
        if (name.isEmpty()) {
            toast("Name is required", true);
            return;
        }
        try {
            bwu.addAccount(new BwuAccount(
                    "", name, addPassword.get(), addPin.get(),
                    addWorldA.get(), 0,
                    BwuTargetType.PRIMARY, BwuAccountType.DEFAULT,
                    addAutoLogin.get(), false));
            refreshData();
            showClassicPopup = false;
            toast("Account added: " + name, false);
        } catch (BwuException e) {
            toast("Add failed: " + e.getMessage(), true);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Data & helpers
    // ═════════════════��════════════════════════════════���════════════════════

    private void refreshData() {
        try {
            int jCount = bwu.jagexAccountCount();
            jagexAccounts = jCount > 0 ? bwu.jagexGetAccounts(jCount) : List.of();
        } catch (BwuException e) {
            log.trace("Jagex refresh: {}", e.getMessage());
        }
        try {
            int cCount = bwu.getAccountCount();
            List<BwuAccount> list = new ArrayList<>(cCount);
            for (int i = 0; i < cCount; i++) list.add(bwu.getAccount(i));
            classicAccounts = List.copyOf(list);
        } catch (BwuException e) {
            log.trace("Classic refresh: {}", e.getMessage());
        }
    }

    private void toast(String msg, boolean error) {
        toastMessage = msg;
        toastTimer = 4f;
        toastIsError = error;
    }

    private void resetAddForm() {
        addName.set("");
        addPassword.set("");
        addPin.set("");
        addWorldA.set(1);
        addAutoLogin.set(false);
    }
}
