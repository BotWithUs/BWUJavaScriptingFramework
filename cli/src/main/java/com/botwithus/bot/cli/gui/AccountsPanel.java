package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.core.loader.BwuAccount;
import com.botwithus.bot.core.loader.BwuAccountType;
import com.botwithus.bot.core.loader.BwuClient;
import com.botwithus.bot.core.loader.BwuException;
import com.botwithus.bot.core.loader.BwuJagexAccount;
import com.botwithus.bot.core.loader.BwuJagexCharacter;
import com.botwithus.bot.core.loader.BwuTargetType;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Account management panel — manage Jagex OAuth accounts (with characters)
 * and classic username/password accounts. Supports launching, session
 * management, and account CRUD operations via the native BwuClient.
 */
public class AccountsPanel implements GuiPanel {

    private static final Logger log = LoggerFactory.getLogger(AccountsPanel.class);

    /** Refresh window for account-list polling (Jagex + classic). */
    private static final long REFRESH_INTERVAL_MS = 2_000L;
    /** Fade-out duration for status banners in seconds. */
    private static final float STATUS_FADE_DURATION_S = 0.3f;
    /** Pixels reserved on the right side of a Jagex card header for badges + counts. */
    private static final float JAGEX_HEADER_BADGE_AREA = 200f;
    /** Seconds remaining on a session below which it is shown as "Expiring". */
    private static final long SESSION_EXPIRING_THRESHOLD_S = 300L;
    /** Maximum visible characters of an account ID before truncation. */
    private static final int ACCOUNT_ID_MAX_LEN = 8;

    private final BwuClient bwu;
    private final ExecutorService executor;

    // Jagex accounts (refreshed from DLL)
    private List<BwuJagexAccount> jagexAccounts = List.of();
    private long jagexAccountsLastRefresh;

    // Classic accounts (refreshed from DLL)
    private List<BwuAccount> classicAccounts = List.of();
    private long classicAccountsLastRefresh;

    // Async operation tracking
    private CompletableFuture<?> pendingOperation;
    private String pendingLabel;

    // Status messages (transient)
    private String statusMessage;
    private float statusTimer;
    private boolean statusIsError;

    // Jagex account expanded state (by UUID)
    private String expandedJagexUuid;

    // Classic account add form
    private boolean showAddClassicForm;
    private final ImString addName = new ImString(256);
    private final ImString addPassword = new ImString(512);
    private final ImString addPin = new ImString(64);
    private final ImInt addWorldA = new ImInt(1);
    private final ImInt addWorldB = new ImInt(0);
    private final ImInt addAccountType = new ImInt(0);
    private final ImBoolean addAutoLogin = new ImBoolean(false);
    private final ImBoolean addAutoRestart = new ImBoolean(false);

    // Classic account edit state
    private String editingUuid;
    private final ImString editName = new ImString(256);
    private final ImString editPassword = new ImString(512);
    private final ImString editPin = new ImString(64);
    private final ImInt editWorldA = new ImInt(0);
    private final ImInt editWorldB = new ImInt(0);
    private final ImBoolean editAutoLogin = new ImBoolean(false);
    private final ImBoolean editAutoRestart = new ImBoolean(false);

    // Confirm delete state
    private String confirmDeleteUuid;
    private boolean confirmDeleteIsJagex;

    // Animation
    private float fadeInTimer;

    public AccountsPanel(BwuClient bwu, ExecutorService executor) {
        this.bwu = bwu;
        this.executor = executor;
    }

    @Override
    public String title() {
        return "Accounts";
    }

    @Override
    public void render(CliContext ctx) {
        float dt = ImGui.getIO().getDeltaTime();
        fadeInTimer = Math.min(fadeInTimer + dt, 1f);

        // Decay status message
        if (statusTimer > 0) {
            statusTimer -= dt;
            if (statusTimer <= 0) {
                statusMessage = null;
            }
        }

        if (bwu == null) {
            renderUnavailable();
            return;
        }

        // Auto-refresh account lists periodically
        long now = System.currentTimeMillis();
        if (now - jagexAccountsLastRefresh > REFRESH_INTERVAL_MS) {
            refreshJagexAccounts();
            jagexAccountsLastRefresh = now;
        }
        if (now - classicAccountsLastRefresh > REFRESH_INTERVAL_MS) {
            refreshClassicAccounts();
            classicAccountsLastRefresh = now;
        }

        // Status banner
        if (statusMessage != null) {
            renderStatusBanner();
        }

        // Pending operation indicator
        if (pendingOperation != null && !pendingOperation.isDone()) {
            renderPendingIndicator();
        } else if (pendingOperation != null && pendingOperation.isDone()) {
            pendingOperation = null;
            pendingLabel = null;
        }

        // -- Jagex Accounts Section --
        renderJagexSection();

        ImGui.spacing();
        ImGui.spacing();

        // -- Classic Accounts Section --
        renderClassicSection();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Unavailable state
    // ═══════════════════════════════════════════════════════════════════════

    private void renderUnavailable() {
        ImGui.spacing();
        ImGui.spacing();
        ImGui.spacing();

        float winW = ImGui.getContentRegionAvailX();
        String icon = Icons.WARNING;
        float iconW = ImGui.calcTextSize(icon).x;
        ImGui.setCursorPosX((winW - iconW) / 2f);
        ImGui.textColored(ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B, 1f, icon);

        ImGui.spacing();

        String msg = "Native Loader Not Available";
        float msgW = ImGui.calcTextSize(msg).x;
        ImGui.setCursorPosX((winW - msgW) / 2f);
        ImGui.text(msg);

        ImGui.spacing();

        float descW = winW * 0.7f;
        ImGui.setCursorPosX((winW - descW) / 2f);
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + descW);
        GuiHelpers.textSecondary(
                "Account management requires the native loader (bwu.dll). "
                + "Start the application with the loader available to manage accounts.");
        ImGui.popTextWrapPos();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Status banner
    // ═══════════════════════════════════════════════════════════════════════

    private void renderStatusBanner() {
        float alpha = Math.min(1f, statusTimer / STATUS_FADE_DURATION_S); // fade out near end
        float r = statusIsError ? ImGuiTheme.RED_R : ImGuiTheme.ACCENT_R;
        float g = statusIsError ? ImGuiTheme.RED_G : ImGuiTheme.ACCENT_G;
        float b = statusIsError ? ImGuiTheme.RED_B : ImGuiTheme.ACCENT_B;

        ImDrawList draw = ImGui.getWindowDrawList();
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        float w = ImGui.getContentRegionAvailX();
        float h = ImGui.getTextLineHeightWithSpacing() + 8;

        // Background
        draw.addRectFilled(x, y, x + w, y + h,
                ImGuiTheme.imCol32(r, g, b, 0.12f * alpha), 4f);
        // Left accent bar
        draw.addRectFilled(x, y, x + 3, y + h,
                ImGuiTheme.imCol32(r, g, b, 0.8f * alpha), 2f);

        ImGui.setCursorPosX(ImGui.getCursorPosX() + ImGui.getStyle().getIndentSpacing() * 0.5f);
        ImGui.pushStyleVar(ImGuiStyleVar.Alpha, alpha);
        ImGui.textColored(r, g, b, alpha, statusMessage);
        ImGui.popStyleVar();
        ImGui.dummy(0, 4);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Pending operation indicator
    // ═══════════════════════════════════════════════════════════════════════

    private void renderPendingIndicator() {
        float pulse = 0.5f + 0.5f * (float) Math.sin(ImGui.getTime() * 4.0);
        String label = pendingLabel != null ? pendingLabel : "Working...";
        ImGui.textColored(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, pulse,
                Icons.SPINNER + "  " + label);
        ImGui.spacing();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Jagex Accounts Section
    // ═══════════════════════════════════════════════════════════════════════

    private void renderJagexSection() {
        // Section header with action buttons
        GuiHelpers.sectionHeader("Jagex Accounts");

        // Action buttons row
        boolean busy = pendingOperation != null && !pendingOperation.isDone();
        if (busy) ImGui.beginDisabled();

        if (GuiHelpers.buttonPrimary(Icons.PLUS + "  Add Account")) {
            startJagexLogin();
        }
        ImGui.sameLine(0, 8);
        if (GuiHelpers.buttonSecondary(Icons.ROTATE + "  Restore")) {
            startJagexRestore();
        }
        ImGui.sameLine(0, 16);
        GuiHelpers.textMuted(jagexAccounts.size() + " account(s)");

        if (busy) ImGui.endDisabled();

        ImGui.spacing();

        if (jagexAccounts.isEmpty()) {
            ImGui.spacing();
            GuiHelpers.textMuted("No Jagex accounts. Add one via OAuth or restore from a previous session.");
            ImGui.spacing();
        } else {
            for (int i = 0; i < jagexAccounts.size(); i++) {
                ImGui.pushID("jagex_" + i);
                renderJagexAccountCard(jagexAccounts.get(i), i);
                ImGui.popID();
                ImGui.spacing();
            }
        }
    }

    private void renderJagexAccountCard(BwuJagexAccount account, int index) {
        float availW = ImGui.getContentRegionAvailX();
        boolean isExpanded = account.uuid().equals(expandedJagexUuid);

        // Use a child window for the card — ImGui handles background, border, and sizing
        ImGui.pushStyleColor(ImGuiCol.ChildBg,
                ImGuiTheme.SURFACE_R, ImGuiTheme.SURFACE_G, ImGuiTheme.SURFACE_B, 1f);
        ImGui.pushStyleColor(ImGuiCol.Border,
                ImGuiTheme.BORDER_R, ImGuiTheme.BORDER_G, ImGuiTheme.BORDER_B, 0.4f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 6f);

        // Estimate height: header + optional expanded content
        float lineH = ImGui.getTextLineHeightWithSpacing();
        float padding = ImGui.getStyle().getWindowPaddingY() * 2;
        float headerH = lineH;
        float expandedH = 0;
        if (isExpanded) {
            int charCount = Math.max(1, account.characters().size());
            expandedH = charCount * lineH               // character lines
                    + ImGui.getFrameHeightWithSpacing()  // action buttons
                    + ImGui.getStyle().getItemSpacingY() * 4; // spacing
        }
        float cardH = padding + headerH + expandedH;

        ImGui.beginChild("##jagexCard_" + account.uuid(), availW, cardH, true);
        ImGui.popStyleColor(2);
        ImGui.popStyleVar();

        // Accent bar on left edge for expanded cards
        if (isExpanded) {
            ImDrawList draw = ImGui.getWindowDrawList();
            float cardX = ImGui.getWindowPosX();
            float cardY = ImGui.getWindowPosY();
            float childH = ImGui.getWindowHeight();
            int accentCol = ImGuiTheme.imCol32(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.6f);
            draw.addRectFilled(cardX, cardY + 2, cardX + 3, cardY + childH - 2, accentCol, 2f);
        }

        // --- Header row ---
        String displayName = account.displayLabel().isEmpty()
                ? "Jagex Account" : account.displayLabel();

        ImGui.pushStyleColor(ImGuiCol.Header, 0, 0, 0, 0);
        ImGui.pushStyleColor(ImGuiCol.HeaderHovered, 0, 0, 0, 0);
        ImGui.pushStyleColor(ImGuiCol.HeaderActive, 0, 0, 0, 0);

        String chevron = isExpanded ? Icons.ANGLE_DOWN : Icons.CHEVRON_R;
        String headerLabel = chevron + "  " + Icons.SHIELD + "  " + displayName
                + "##header_" + account.uuid();

        float headerW = ImGui.getContentRegionAvailX() - JAGEX_HEADER_BADGE_AREA;
        if (ImGui.selectable(headerLabel, isExpanded, 0, headerW, lineH)) {
            expandedJagexUuid = isExpanded ? null : account.uuid();
        }
        ImGui.popStyleColor(3);

        // Session status badge (right-aligned within the child).
        // Reserve a slightly narrower area than the header carve-out so the
        // selectable does not overlap the badge.
        ImGui.sameLine(ImGui.getContentRegionAvailX() - (JAGEX_HEADER_BADGE_AREA - 20f));
        renderSessionBadge(account);

        ImGui.sameLine(0, 8);
        GuiHelpers.textMuted(account.characters().size() + " char(s)");

        // --- Expanded content ---
        if (isExpanded) {
            ImGui.spacing();
            renderCharacterList(account);
            ImGui.spacing();
            renderJagexActions(account);
        }

        ImGui.endChild();
    }

    private void renderSessionBadge(BwuJagexAccount account) {
        long now = Instant.now().getEpochSecond();
        long expires = account.sessionExpiresAt();
        boolean hasSession = account.sessionId() != null && !account.sessionId().isEmpty();

        if (!hasSession || expires <= 0) {
            GuiHelpers.statusBadge("No Session", ImGuiTheme.DIM_TEXT_R, ImGuiTheme.DIM_TEXT_G, ImGuiTheme.DIM_TEXT_B);
        } else if (expires < now) {
            GuiHelpers.statusBadge("Expired", ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B);
        } else if (expires - now < SESSION_EXPIRING_THRESHOLD_S) {
            GuiHelpers.statusBadge("Expiring", ImGuiTheme.YELLOW_R, ImGuiTheme.YELLOW_G, ImGuiTheme.YELLOW_B);
        } else {
            GuiHelpers.statusBadge("Active", ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B);
        }
    }

    private void renderCharacterList(BwuJagexAccount account) {
        List<BwuJagexCharacter> chars = account.characters();
        if (chars.isEmpty()) {
            ImGui.indent();
            GuiHelpers.textMuted("No characters linked to this account.");
            ImGui.unindent();
            return;
        }

        ImGui.indent();
        for (int i = 0; i < chars.size(); i++) {
            BwuJagexCharacter ch = chars.get(i);
            boolean isSelected = (i == account.selectedCharacter());

            // Selection indicator
            String prefix = isSelected ? Icons.STAR : Icons.CIRCLE;
            if (isSelected) {
                ImGui.textColored(ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 1f, prefix);
            } else {
                GuiHelpers.textMuted(prefix);
            }
            ImGui.sameLine(0, 6);

            // Character name
            if (isSelected) {
                ImGui.text(ch.displayName());
            } else {
                GuiHelpers.textSecondary(ch.displayName());
            }

            // Account ID hint
            if (ch.accountId() != null && !ch.accountId().isEmpty()) {
                ImGui.sameLine(0, 8);
                String shortId = ch.accountId().length() > ACCOUNT_ID_MAX_LEN
                        ? ch.accountId().substring(0, ACCOUNT_ID_MAX_LEN) + "..." : ch.accountId();
                GuiHelpers.textMuted(shortId);
            }
        }
        ImGui.unindent();
    }

    private void renderJagexActions(BwuJagexAccount account) {
        boolean busy = pendingOperation != null && !pendingOperation.isDone();
        if (busy) ImGui.beginDisabled();

        // Launch button — uses the account's selected character
        ImGui.indent();
        ImGui.pushStyleColor(ImGuiCol.Text, 0.04f, 0.04f, 0.1f, 1f);
        ImGui.pushStyleColor(ImGuiCol.Button,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.85f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                ImGuiTheme.ACCENT_R * 0.85f, ImGuiTheme.ACCENT_G * 0.85f, ImGuiTheme.ACCENT_B * 0.85f, 1f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                ImGuiTheme.ACCENT_R * 0.7f, ImGuiTheme.ACCENT_G * 0.7f, ImGuiTheme.ACCENT_B * 0.7f, 1f);

        if (ImGui.button(Icons.PLAY + "  Launch")) {
            launchJagexAccount(account);
        }
        ImGui.popStyleColor(4);

        ImGui.sameLine(0, 6);
        if (GuiHelpers.buttonSecondary(Icons.ROTATE + "  Refresh Chars")) {
            refreshJagexCharacters(account.uuid());
        }

        ImGui.sameLine(0, 6);
        if (GuiHelpers.buttonSecondary(Icons.SHIELD + "  Ensure Session")) {
            ensureJagexSession(account.uuid());
        }

        ImGui.sameLine(0, 6);
        if (GuiHelpers.buttonSecondary(Icons.BOLT + "  Select Char")) {
            ImGui.openPopup("char_select_" + account.uuid());
        }

        // Character selection popup
        if (ImGui.beginPopup("char_select_" + account.uuid())) {
            for (int i = 0; i < account.characters().size(); i++) {
                BwuJagexCharacter ch = account.characters().get(i);
                boolean sel = (i == account.selectedCharacter());
                if (ImGui.selectable((sel ? Icons.STAR + "  " : "    ") + ch.displayName(), sel)) {
                    selectJagexCharacter(account.uuid(), i);
                }
            }
            ImGui.endPopup();
        }

        ImGui.sameLine(0, 16);

        // Delete with confirmation
        if (confirmDeleteIsJagex && account.uuid().equals(confirmDeleteUuid)) {
            ImGui.pushStyleColor(ImGuiCol.Text, ImGuiTheme.RED_R, ImGuiTheme.RED_G, ImGuiTheme.RED_B, 1f);
            ImGui.text("Remove?");
            ImGui.popStyleColor();
            ImGui.sameLine(0, 6);
            if (GuiHelpers.buttonPrimary(Icons.CHECK + "  Yes")) {
                removeJagexAccount(account.uuid());
                confirmDeleteUuid = null;
            }
            ImGui.sameLine(0, 4);
            if (GuiHelpers.buttonSecondary(Icons.XMARK + "  No")) {
                confirmDeleteUuid = null;
            }
        } else {
            if (GuiHelpers.smallButtonDanger(Icons.TRASH + "  Remove")) {
                confirmDeleteUuid = account.uuid();
                confirmDeleteIsJagex = true;
            }
        }

        ImGui.unindent();

        if (busy) ImGui.endDisabled();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Classic Accounts Section
    // ═══════════════════════════════════════════════════════════════════════

    private void renderClassicSection() {
        GuiHelpers.sectionHeader("Classic Accounts");

        boolean busy = pendingOperation != null && !pendingOperation.isDone();
        if (busy) ImGui.beginDisabled();

        if (showAddClassicForm) {
            if (GuiHelpers.buttonSecondary(Icons.XMARK + "  Cancel")) {
                showAddClassicForm = false;
            }
        } else {
            if (GuiHelpers.buttonPrimary(Icons.PLUS + "  Add Account")) {
                showAddClassicForm = true;
                resetAddForm();
            }
        }
        ImGui.sameLine(0, 16);
        GuiHelpers.textMuted(classicAccounts.size() + " account(s)");

        if (busy) ImGui.endDisabled();

        ImGui.spacing();

        // Add form
        if (showAddClassicForm) {
            renderAddClassicForm();
            ImGui.spacing();
        }

        // Accounts table
        if (classicAccounts.isEmpty()) {
            GuiHelpers.textMuted("No classic accounts.");
        } else {
            renderClassicTable();
        }
    }

    private void renderAddClassicForm() {
        ImGui.pushStyleColor(ImGuiCol.ChildBg,
                ImGuiTheme.SURFACE_R, ImGuiTheme.SURFACE_G, ImGuiTheme.SURFACE_B, 1f);
        ImGui.pushStyleColor(ImGuiCol.Border,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.3f);
        // Full available width, auto height
        ImGui.beginChild("##addClassicForm", ImGui.getContentRegionAvailX(), 0, true);
        ImGui.popStyleColor(2);

        ImGui.text(Icons.PLUS + "  New Classic Account");
        ImGui.spacing();
        GuiHelpers.subtleSeparator();
        ImGui.spacing();

        float fieldW = ImGui.getContentRegionAvailX();

        // Name
        GuiHelpers.textSecondary("Name/Email");
        ImGui.pushItemWidth(fieldW);
        ImGui.inputText("##addName", addName);
        ImGui.popItemWidth();

        // Password
        GuiHelpers.textSecondary("Password");
        ImGui.pushItemWidth(fieldW);
        ImGui.inputText("##addPass", addPassword, ImGuiInputTextFlags.Password);
        ImGui.popItemWidth();

        // PIN + World on same row
        GuiHelpers.textSecondary("Bank PIN");
        ImGui.sameLine(0, 8);
        ImGui.pushItemWidth(ImGui.getFrameHeight() * 3);
        ImGui.inputText("##addPin", addPin);
        ImGui.popItemWidth();

        ImGui.sameLine(0, 16);
        GuiHelpers.textSecondary("World A");
        ImGui.sameLine(0, 4);
        ImGui.pushItemWidth(ImGui.getFrameHeight() * 3);
        ImGui.inputInt("##addWa", addWorldA, 0);
        ImGui.popItemWidth();
        ImGui.sameLine(0, 16);
        GuiHelpers.textSecondary("World B");
        ImGui.sameLine(0, 4);
        ImGui.pushItemWidth(ImGui.getFrameHeight() * 3);
        ImGui.inputInt("##addWb", addWorldB, 0);
        ImGui.popItemWidth();

        // Account type
        GuiHelpers.textSecondary("Type");
        ImGui.sameLine(0, 8);
        ImGui.pushItemWidth(ImGui.getFrameHeight() * 6);
        ImGui.combo("##addType", addAccountType, new String[]{"Default", "Managed", "Platform"});
        ImGui.popItemWidth();

        // Flags
        ImGui.spacing();
        ImGui.checkbox("Auto-login", addAutoLogin);
        ImGui.sameLine(0, 16);
        ImGui.checkbox("Auto-restart", addAutoRestart);

        ImGui.spacing();
        ImGui.spacing();

        // Submit
        ImGui.pushStyleColor(ImGuiCol.Text, 0.04f, 0.04f, 0.1f, 1f);
        ImGui.pushStyleColor(ImGuiCol.Button,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.85f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                ImGuiTheme.ACCENT_R * 0.85f, ImGuiTheme.ACCENT_G * 0.85f, ImGuiTheme.ACCENT_B * 0.85f, 1f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                ImGuiTheme.ACCENT_R * 0.7f, ImGuiTheme.ACCENT_G * 0.7f, ImGuiTheme.ACCENT_B * 0.7f, 1f);
        if (ImGui.button(Icons.CHECK + "  Add Account")) {
            addClassicAccount();
        }
        ImGui.popStyleColor(4);

        ImGui.endChild();
    }

    private void renderClassicTable() {
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingStretchProp;
        if (ImGui.beginTable("classicAccounts", 7, flags)) {
            ImGui.tableSetupColumn("Name", 0, 1.4f);
            ImGui.tableSetupColumn("Type", 0, 0.6f);
            ImGui.tableSetupColumn("World", 0, 0.5f);
            ImGui.tableSetupColumn("Target", 0, 0.5f);
            ImGui.tableSetupColumn("Auto", 0, 0.5f);
            ImGui.tableSetupColumn("Actions", 0, 1.8f);
            ImGui.tableSetupColumn("", 0, 0.4f);
            ImGui.tableHeadersRow();

            for (int i = 0; i < classicAccounts.size(); i++) {
                BwuAccount acct = classicAccounts.get(i);
                boolean isEditing = acct.uuid().equals(editingUuid);

                ImGui.tableNextRow();
                ImGui.pushID("classic_" + i);

                if (isEditing) {
                    renderClassicEditRow(acct);
                } else {
                    renderClassicRow(acct, i);
                }

                ImGui.popID();
            }

            ImGui.endTable();
        }
    }

    private void renderClassicRow(BwuAccount acct, int index) {
        // Name
        ImGui.tableSetColumnIndex(0);
        ImGui.text(acct.name());

        // Type
        ImGui.tableSetColumnIndex(1);
        String typeLabel = switch (acct.accountType()) {
            case DEFAULT -> "Default";
            case MANAGED -> "Managed";
            case PLATFORM -> "Platform";
        };
        GuiHelpers.textSecondary(typeLabel);

        // World
        ImGui.tableSetColumnIndex(2);
        String worldText = "W" + acct.worldA();
        if (acct.worldB() > 0) worldText += " / W" + acct.worldB();
        ImGui.text(worldText);

        // Target
        ImGui.tableSetColumnIndex(3);
        GuiHelpers.textSecondary(acct.targetType() == BwuTargetType.PRIMARY ? "Primary" : "Secondary");

        // Auto flags
        ImGui.tableSetColumnIndex(4);
        if (acct.autoLogin()) {
            ImGui.textColored(ImGuiTheme.GREEN_R, ImGuiTheme.GREEN_G, ImGuiTheme.GREEN_B, 1f, Icons.CHECK);
        }
        if (acct.autoRestart()) {
            ImGui.sameLine(0, 4);
            ImGui.textColored(ImGuiTheme.BLUE_ACCENT_R, ImGuiTheme.BLUE_ACCENT_G, ImGuiTheme.BLUE_ACCENT_B, 1f,
                    Icons.ROTATE);
        }

        // Actions
        ImGui.tableSetColumnIndex(5);
        boolean busy = pendingOperation != null && !pendingOperation.isDone();
        if (busy) ImGui.beginDisabled();

        // Launch button
        ImGui.pushStyleColor(ImGuiCol.Text, 0.04f, 0.04f, 0.1f, 1f);
        ImGui.pushStyleColor(ImGuiCol.Button,
                ImGuiTheme.ACCENT_R, ImGuiTheme.ACCENT_G, ImGuiTheme.ACCENT_B, 0.85f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered,
                ImGuiTheme.ACCENT_R * 0.85f, ImGuiTheme.ACCENT_G * 0.85f, ImGuiTheme.ACCENT_B * 0.85f, 1f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive,
                ImGuiTheme.ACCENT_R * 0.7f, ImGuiTheme.ACCENT_G * 0.7f, ImGuiTheme.ACCENT_B * 0.7f, 1f);
        if (ImGui.smallButton(Icons.PLAY + " Launch")) {
            launchClassicAccount(acct);
        }
        ImGui.popStyleColor(4);

        ImGui.sameLine(0, 4);
        if (ImGui.smallButton(Icons.WRENCH + " Edit")) {
            startEditClassicAccount(acct);
        }

        if (busy) ImGui.endDisabled();

        // Delete column
        ImGui.tableSetColumnIndex(6);
        if (!confirmDeleteIsJagex && acct.uuid().equals(confirmDeleteUuid)) {
            if (ImGui.smallButton(Icons.CHECK)) {
                removeClassicAccount(acct.uuid());
                confirmDeleteUuid = null;
            }
            ImGui.sameLine(0, 2);
            if (ImGui.smallButton(Icons.XMARK)) {
                confirmDeleteUuid = null;
            }
        } else {
            if (GuiHelpers.smallButtonDanger(Icons.TRASH)) {
                confirmDeleteUuid = acct.uuid();
                confirmDeleteIsJagex = false;
            }
        }
    }

    private void renderClassicEditRow(BwuAccount acct) {
        // Name
        ImGui.tableSetColumnIndex(0);
        ImGui.pushItemWidth(-1);
        ImGui.inputText("##editName", editName);
        ImGui.popItemWidth();

        // Type (read-only during edit)
        ImGui.tableSetColumnIndex(1);
        GuiHelpers.textSecondary(acct.accountType().name());

        // World
        ImGui.tableSetColumnIndex(2);
        ImGui.pushItemWidth(40);
        ImGui.inputInt("##editWa", editWorldA, 0);
        ImGui.popItemWidth();
        ImGui.sameLine(0, 2);
        ImGui.pushItemWidth(40);
        ImGui.inputInt("##editWb", editWorldB, 0);
        ImGui.popItemWidth();

        // Target (read-only)
        ImGui.tableSetColumnIndex(3);
        GuiHelpers.textSecondary(acct.targetType().name());

        // Auto
        ImGui.tableSetColumnIndex(4);
        ImGui.checkbox("##editAL", editAutoLogin);
        ImGui.sameLine(0, 4);
        ImGui.checkbox("##editAR", editAutoRestart);

        // Save / Cancel
        ImGui.tableSetColumnIndex(5);
        if (GuiHelpers.buttonPrimary(Icons.CHECK + " Save")) {
            saveEditClassicAccount(acct);
        }
        ImGui.sameLine(0, 4);
        if (GuiHelpers.buttonSecondary(Icons.XMARK + " Cancel")) {
            editingUuid = null;
        }

        ImGui.tableSetColumnIndex(6);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Data refresh (from DLL)
    // ═══════════════════════════════════════════════════════════════════════

    private void refreshJagexAccounts() {
        try {
            int count = bwu.jagexAccountCount();
            if (count > 0) {
                jagexAccounts = bwu.jagexGetAccounts(count);
            } else {
                jagexAccounts = List.of();
            }
        } catch (BwuException e) {
            log.trace("Failed to refresh Jagex accounts: {}", e.getMessage());
        }
    }

    private void refreshClassicAccounts() {
        try {
            int count = bwu.getAccountCount();
            List<BwuAccount> accounts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                accounts.add(bwu.getAccount(i));
            }
            classicAccounts = List.copyOf(accounts);
        } catch (BwuException e) {
            log.trace("Failed to refresh classic accounts: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Jagex account operations
    // ═══════════════════════════════════════════════════════════════════════

    private void startJagexLogin() {
        pendingLabel = "Waiting for Jagex OAuth (browser)...";
        pendingOperation = CompletableFuture.runAsync(() -> {
            try {
                BwuJagexAccount acct = bwu.jagexLogin();
                setStatus("Jagex account added: " + acct.displayLabel(), false);
                refreshJagexAccounts();
            } catch (BwuException e) {
                log.error("Jagex login failed: {}", e.getMessage());
                setStatus("Jagex login failed: " + e.getMessage(), true);
            }
        }, executor);
    }

    private void startJagexRestore() {
        pendingLabel = "Restoring Jagex accounts from Credential Manager...";
        pendingOperation = CompletableFuture.runAsync(() -> {
            try {
                bwu.jagexRestoreAccounts();
                refreshJagexAccounts();
                int count = bwu.jagexAccountCount();
                setStatus("Restored " + count + " Jagex account(s)", false);
            } catch (BwuException e) {
                log.error("Jagex restore failed: {}", e.getMessage());
                setStatus("Restore failed: " + e.getMessage(), true);
            }
        }, executor);
    }

    private void refreshJagexCharacters(String uuid) {
        pendingLabel = "Refreshing characters...";
        pendingOperation = CompletableFuture.runAsync(() -> {
            try {
                bwu.jagexEnsureSession(uuid);
                bwu.jagexRefreshCharacters(uuid);
                refreshJagexAccounts();
                setStatus("Characters refreshed", false);
            } catch (BwuException e) {
                log.error("Refresh characters failed: {}", e.getMessage());
                setStatus("Refresh failed: " + e.getMessage(), true);
            }
        }, executor);
    }

    private void ensureJagexSession(String uuid) {
        pendingLabel = "Ensuring session...";
        pendingOperation = CompletableFuture.runAsync(() -> {
            try {
                bwu.jagexEnsureSession(uuid);
                refreshJagexAccounts();
                setStatus("Session refreshed", false);
            } catch (BwuException e) {
                log.error("Ensure session failed: {}", e.getMessage());
                setStatus("Session refresh failed: " + e.getMessage(), true);
            }
        }, executor);
    }

    private void selectJagexCharacter(String uuid, int index) {
        try {
            bwu.jagexSelectCharacter(uuid, index);
            refreshJagexAccounts();
        } catch (BwuException e) {
            setStatus("Select character failed: " + e.getMessage(), true);
        }
    }

    private void removeJagexAccount(String uuid) {
        try {
            bwu.jagexRemoveAccount(uuid);
            refreshJagexAccounts();
            if (uuid.equals(expandedJagexUuid)) expandedJagexUuid = null;
            setStatus("Jagex account removed", false);
        } catch (BwuException e) {
            setStatus("Remove failed: " + e.getMessage(), true);
        }
    }

    private void launchJagexAccount(BwuJagexAccount account) {
        pendingLabel = "Launching " + account.displayLabel() + "...";
        pendingOperation = CompletableFuture.runAsync(() -> {
            try {
                bwu.jagexEnsureSession(account.uuid());
                // Classic account UUID is not used for Jagex launches — the DLL
                // passes session credentials (JX_SESSION_ID, etc.) via env vars.
                String bwuUuid = classicAccounts.isEmpty() ? "" : classicAccounts.getFirst().uuid();
                bwu.jagexLaunch(account.uuid(), bwuUuid, account.selectedCharacter());
                // Poll until the background launch thread finishes (DLL waits up to ~120s for game window)
                while (bwu.getStatus().activeLaunches() > 0) {
                    Thread.sleep(500);
                }
                String err = bwu.getLastError();
                if (err != null && !err.isEmpty()) {
                    setStatus("Launch failed: " + err, true);
                } else {
                    setStatus("Launched " + account.displayLabel(), false);
                }
            } catch (BwuException e) {
                log.error("Jagex launch failed: {}", e.getMessage());
                setStatus("Launch failed: " + e.getMessage(), true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                setStatus("Launch interrupted", true);
            }
        }, executor);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Classic account operations
    // ═══════════════════════════════════════════════════════════════════════

    private void addClassicAccount() {
        String name = addName.get().trim();
        if (name.isEmpty()) {
            setStatus("Account name is required", true);
            return;
        }

        try {
            BwuAccount account = new BwuAccount(
                    "", // empty UUID — DLL auto-generates
                    name,
                    addPassword.get(),
                    addPin.get(),
                    addWorldA.get(),
                    addWorldB.get(),
                    BwuTargetType.PRIMARY,
                    BwuAccountType.fromValue(addAccountType.get()),
                    addAutoLogin.get(),
                    addAutoRestart.get()
            );
            bwu.addAccount(account);
            refreshClassicAccounts();
            showAddClassicForm = false;
            setStatus("Account added: " + name, false);
        } catch (BwuException e) {
            setStatus("Add failed: " + e.getMessage(), true);
        }
    }

    private void startEditClassicAccount(BwuAccount acct) {
        editingUuid = acct.uuid();
        editName.set(acct.name());
        editPassword.set(acct.password());
        editPin.set(acct.pin());
        editWorldA.set(acct.worldA());
        editWorldB.set(acct.worldB());
        editAutoLogin.set(acct.autoLogin());
        editAutoRestart.set(acct.autoRestart());
    }

    private void saveEditClassicAccount(BwuAccount original) {
        try {
            BwuAccount updated = new BwuAccount(
                    original.uuid(),
                    editName.get().trim(),
                    editPassword.get(),
                    editPin.get(),
                    editWorldA.get(),
                    editWorldB.get(),
                    original.targetType(),
                    original.accountType(),
                    editAutoLogin.get(),
                    editAutoRestart.get()
            );
            bwu.updateAccount(updated);
            refreshClassicAccounts();
            editingUuid = null;
            setStatus("Account updated", false);
        } catch (BwuException e) {
            setStatus("Update failed: " + e.getMessage(), true);
        }
    }

    private void removeClassicAccount(String uuid) {
        try {
            bwu.removeAccount(uuid);
            refreshClassicAccounts();
            setStatus("Account removed", false);
        } catch (BwuException e) {
            setStatus("Remove failed: " + e.getMessage(), true);
        }
    }

    private void launchClassicAccount(BwuAccount acct) {
        pendingLabel = "Launching " + acct.name() + "...";
        pendingOperation = CompletableFuture.runAsync(() -> {
            try {
                switch (acct.accountType()) {
                    case DEFAULT -> bwu.launchDefault(acct.uuid());
                    case PLATFORM -> bwu.launchPlatform(acct.uuid());
                    case MANAGED -> bwu.launchManaged(null, acct.uuid());
                }
                while (bwu.getStatus().activeLaunches() > 0) {
                    Thread.sleep(500);
                }
                String err = bwu.getLastError();
                if (err != null && !err.isEmpty()) {
                    setStatus("Launch failed: " + err, true);
                } else {
                    setStatus("Launched " + acct.name(), false);
                }
            } catch (BwuException e) {
                log.error("Classic launch failed: {}", e.getMessage());
                setStatus("Launch failed: " + e.getMessage(), true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                setStatus("Launch interrupted", true);
            }
        }, executor);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private void setStatus(String message, boolean isError) {
        this.statusMessage = message;
        this.statusTimer = 4f;
        this.statusIsError = isError;
    }

    private void resetAddForm() {
        addName.set("");
        addPassword.set("");
        addPin.set("");
        addWorldA.set(1);
        addWorldB.set(0);
        addAccountType.set(0);
        addAutoLogin.set(false);
        addAutoRestart.set(false);
    }
}
