package com.botwithus.bot.api.launcher;

import java.nio.file.Path;
import java.util.List;

/**
 * Optional interface for launching game clients and managing accounts via
 * the BotWithUs loader DLL.
 *
 * <p>Obtained from {@link com.botwithus.bot.api.script.ManagementContext#getLauncher()}.
 * Returns {@link java.util.Optional#empty()} when the native loader is unavailable.
 *
 * <h3>Typical flow (classic account)</h3>
 * <pre>{@code
 * launcher.init();
 * launcher.login();                                // BotWithUs SSO + auto-download
 * // poll getStatus() for download_progress / module_ready
 * launcher.addAccount(new Account(...));
 * launcher.launchDefault(accountUuid);             // background: launch + inject
 * // Java discovers injected client via named pipe
 * }</pre>
 *
 * <h3>Typical flow (Jagex account)</h3>
 * <pre>{@code
 * launcher.init();
 * launcher.login();                                // BotWithUs auth + auto-download
 * JagexAccount acct = launcher.jagexLogin();        // OAuth browser flow
 * launcher.jagexSelectCharacter(acct.uuid(), 0);
 * launcher.jagexEnsureSession(acct.uuid());
 * launcher.addAccount(new Account(...));            // BWU account with worlds/pin
 * launcher.jagexLaunch(acct.uuid(), accountUuid);   // background: launch + inject
 * }</pre>
 */
public interface GameLauncher extends AutoCloseable {

    // ── Nested types ───────────────────────────────────────────────────────

    enum TargetType { PRIMARY, SECONDARY }

    enum AccountType { DEFAULT, MANAGED, PLATFORM }

    record UserInfo(String id, String name, long sessionLimit, long expirationTs) {}

    record Status(int loginStage, int maxLoginStage, boolean loggedIn,
                  boolean downloading, int downloadProgress, boolean moduleReady,
                  int activeLaunches, String lastError) {}

    record Account(
            String uuid, String name, String password, String pin,
            int worldA, int worldB,
            TargetType targetType, AccountType accountType,
            boolean autoLogin, boolean autoRestart) {}

    record ProviderAccount(String name, boolean selected) {}

    record JagexCharacter(String accountId, String displayName, String userHash) {}

    record JagexAccount(
            String uuid, String subject, String displayLabel,
            List<JagexCharacter> characters, int selectedCharacter,
            String sessionId, long sessionExpiresAt) {}

    // ── Lifecycle ──────────────────────────────────────────────────────────

    void init();

    void shutdown();

    Status getStatus();

    String getLastError();

    String getVersion();

    // ── Authentication (BotWithUs) ─────────────────────────────────────────

    /** Start SSO login flow. Opens browser, <strong>blocks</strong> up to 5 min. Auto-starts module download on success. */
    void login();

    void loginWithToken(String token);

    boolean isLoggedIn();

    String getToken();

    UserInfo getUser();

    void logout();

    void saveToken(Path path);

    void loadToken(Path path);

    // ── Module Management ──────────────────────────────────────────────────

    /** Trigger a module refresh. Checks for a newer version in a background thread. */
    void refreshModule();

    // ── Classic Account Management ─────────────────────────────────────────

    void addAccount(Account account);

    void removeAccount(String uuid);

    int getAccountCount();

    Account getAccount(int index);

    Account findAccount(String uuid);

    void updateAccount(Account account);

    void clearAccounts();

    // ── Launch (Non-blocking Triggers) ─────────────────────────────────────

    /** Launch via direct executable. Non-blocking. */
    void launchDefault(String accountUuid);

    /** Launch via Steam protocol URL. Non-blocking. */
    void launchPlatform(String accountUuid);

    /** Launch via Jagex Launcher CLI. Non-blocking. */
    void launchManaged(String accountName, String accountUuid);

    void setProviderPath(Path path);

    String getProviderPath();

    // ── Provider Account Discovery ─────────────────────────────────────────

    void refreshProviderAccounts();

    List<ProviderAccount> getProviderAccounts(int maxCount);

    // ── Jagex Account Authentication ───────────────────────────────────────

    /** OAuth browser flow. <strong>Blocks</strong> up to 5 min. */
    JagexAccount jagexLogin();

    JagexAccount jagexGetAccount(String uuid);

    List<JagexAccount> jagexGetAccounts(int maxCount);

    int jagexAccountCount();

    void jagexRemoveAccount(String uuid);

    void jagexSelectCharacter(String uuid, int characterIndex);

    void jagexEnsureSession(String uuid);

    /** Launch rs2client.exe with Jagex session. Non-blocking. */
    void jagexLaunch(String jagexUuid, String accountUuid);

    // ── Utility ────────────────────────────────────────────────────────────

    String generateUuid();

    @Override
    void close();
}
