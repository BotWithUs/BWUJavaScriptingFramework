package com.botwithus.bot.api.launcher;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Optional interface for launching game clients and managing accounts via
 * the BotWithUs loader DLL.
 *
 * <p>Obtained from {@link com.botwithus.bot.api.script.ManagementContext#getLauncher()}.
 * Returns {@link Optional#empty()} when the native loader is unavailable.
 *
 * <h3>Typical flow (classic account)</h3>
 * <pre>{@code
 * launcher.init();
 * launcher.login();                                // BotWithUs SSO
 * launcher.downloadModule();
 * launcher.addAccount(new Account("", "user@example.com", "pass", "", 1, 2,
 *                     TargetType.PRIMARY, AccountType.DEFAULT, true, false));
 * launcher.launchDefault();
 * int[] pids = launcher.findProcesses(10);
 * launcher.loadModule(pids[0], new LoadParams("", "user@example.com", "pass", "", 1, 2, true));
 * }</pre>
 *
 * <h3>Typical flow (Jagex account)</h3>
 * <pre>{@code
 * launcher.init();
 * launcher.login();
 * launcher.downloadModule();
 * JagexAccount acct = launcher.jagexLogin();        // OAuth browser flow
 * launcher.jagexSelectCharacter(acct.uuid(), 0);
 * launcher.jagexEnsureSession(acct.uuid());
 * launcher.jagexLaunch(acct.uuid());
 * int[] pids = launcher.findProcesses(10);
 * launcher.loadModule(pids[0], new LoadParams(...));
 * }</pre>
 */
public interface GameLauncher extends AutoCloseable {

    // ── Nested types ───────────────────────────────────────────────────────

    enum TargetType { PRIMARY, SECONDARY }

    enum AccountType { DEFAULT, MANAGED, PLATFORM }

    enum ProcessEventType { NONE, START, EXIT }

    record UserInfo(String id, String name, long sessionLimit, long expirationTs) {}

    record Status(int loginStage, int maxLoginStage, boolean loggedIn,
                  boolean downloading, String downloadRate, String lastError) {}

    record Account(
            String uuid, String name, String password, String pin,
            int worldA, int worldB,
            TargetType targetType, AccountType accountType,
            boolean autoLogin, boolean autoRestart) {}

    record LoadParams(
            String pin, String email, String password, String uuid,
            int worldA, int worldB, boolean autoLogin) {}

    record ProcessEvent(int pid, ProcessEventType eventType) {}

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

    /** Start SSO login flow. Opens browser, <strong>blocks</strong> up to 5 min. */
    void login();

    void loginWithToken(String token);

    boolean isLoggedIn();

    String getToken();

    UserInfo getUser();

    void logout();

    void saveToken(Path path);

    void loadToken(Path path);

    // ── Module Management ──────────────────────────────────────────────────

    /** Download the agent module. Requires login. <strong>Blocks</strong>. */
    void downloadModule();

    boolean hasModule();

    byte[] getModuleBytes();

    // ── Classic Account Management ─────────────────────────────────────────

    void addAccount(Account account);

    void removeAccount(String uuid);

    int getAccountCount();

    Account getAccount(int index);

    Account findAccount(String uuid);

    void updateAccount(Account account);

    void clearAccounts();

    // ── Process Management ─────────────────────────────────────────────────

    void launchDefault();

    void launchPlatform();

    void launchManaged(String accountName);

    void setProviderPath(Path path);

    String getProviderPath();

    int[] findProcesses(int maxCount);

    Optional<ProcessEvent> pollProcessEvent();

    // ── Module Loading ─────────────────────────────────────────────────────

    void loadModule(int pid, LoadParams params);

    void loadModuleRaw(int pid, byte[] data, LoadParams params);

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

    void jagexLaunch(String uuid);

    // ── Utility ────────────────────────────────────────────────────────────

    String generateUuid();

    @Override
    void close();
}
