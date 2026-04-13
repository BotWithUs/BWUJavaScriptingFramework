package com.botwithus.bot.core.loader;

import com.botwithus.bot.api.launcher.GameLauncher;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Adapts {@link BwuClient} to the {@link GameLauncher} interface so that
 * management scripts (which depend only on the {@code api} module) can use
 * the loader without a direct dependency on {@code core.loader} types.
 */
public final class BwuGameLauncher implements GameLauncher {

    private final BwuClient client;

    public BwuGameLauncher(BwuClient client) {
        this.client = client;
    }

    /** The underlying native client. */
    public BwuClient client() {
        return client;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override public void init()            { client.init(); }
    @Override public void shutdown()        { client.shutdown(); }
    @Override public String getLastError()  { return client.getLastError(); }
    @Override public String getVersion()    { return client.getVersion(); }

    @Override
    public Status getStatus() {
        var s = client.getStatus();
        return new Status(s.loginStage(), s.maxLoginStage(), s.loggedIn(),
                s.downloading(), s.downloadRate(), s.lastError());
    }

    // ── Authentication ─────────────────────────────────────────────────────

    @Override public void login()                       { client.login(); }
    @Override public void loginWithToken(String token)  { client.loginWithToken(token); }
    @Override public boolean isLoggedIn()                { return client.isLoggedIn(); }
    @Override public String getToken()                  { return client.getToken(); }
    @Override public void logout()                      { client.logout(); }
    @Override public void saveToken(Path path)          { client.saveToken(path); }
    @Override public void loadToken(Path path)          { client.loadToken(path); }

    @Override
    public UserInfo getUser() {
        var u = client.getUser();
        return new UserInfo(u.id(), u.name(), u.sessionLimit(), u.expirationTs());
    }

    // ── Module Management ──────────────────────────────────────────────────

    @Override public void downloadModule()   { client.downloadModule(); }
    @Override public boolean hasModule()      { return client.hasModule(); }
    @Override public byte[] getModuleBytes() { return client.getModuleBytes(); }

    // ── Classic Account Management ─────────────────────────────────────────

    @Override
    public void addAccount(Account account) {
        client.addAccount(toNative(account));
    }

    @Override public void removeAccount(String uuid) { client.removeAccount(uuid); }
    @Override public int getAccountCount()           { return client.getAccountCount(); }
    @Override public void clearAccounts()             { client.clearAccounts(); }

    @Override
    public Account getAccount(int index) {
        return fromNative(client.getAccount(index));
    }

    @Override
    public Account findAccount(String uuid) {
        return fromNative(client.findAccount(uuid));
    }

    @Override
    public void updateAccount(Account account) {
        client.updateAccount(toNative(account));
    }

    // ── Process Management ─────────────────────────────────────────────────

    @Override public void launchDefault()                    { client.launchDefault(); }
    @Override public void launchPlatform()                   { client.launchPlatform(); }
    @Override public void launchManaged(String accountName)  { client.launchManaged(accountName); }
    @Override public void setProviderPath(Path path)        { client.setProviderPath(path); }
    @Override public String getProviderPath()                { return client.getProviderPath(); }
    @Override public int[] findProcesses(int maxCount)      { return client.findProcesses(maxCount); }

    @Override
    public Optional<ProcessEvent> pollProcessEvent() {
        return client.pollProcessEvent().map(e -> new ProcessEvent(
                e.pid(), ProcessEventType.valueOf(e.eventType().name())));
    }

    // ── Module Loading ─────────────────────────────────────────────────────

    @Override
    public void loadModule(int pid, LoadParams params) {
        client.loadModule(pid, toNative(params));
    }

    @Override
    public void loadModuleRaw(int pid, byte[] data, LoadParams params) {
        client.loadModuleRaw(pid, data, toNative(params));
    }

    // ── Provider Account Discovery ─────────────────────────────────────────

    @Override public void refreshProviderAccounts() { client.refreshProviderAccounts(); }

    @Override
    public List<ProviderAccount> getProviderAccounts(int maxCount) {
        return client.getProviderAccounts(maxCount).stream()
                .map(p -> new ProviderAccount(p.name(), p.selected()))
                .toList();
    }

    // ── Jagex Account Authentication ───────────────────────────────────────

    @Override
    public JagexAccount jagexLogin() {
        return fromNative(client.jagexLogin());
    }

    @Override
    public JagexAccount jagexGetAccount(String uuid) {
        return fromNative(client.jagexGetAccount(uuid));
    }

    @Override
    public List<JagexAccount> jagexGetAccounts(int maxCount) {
        return client.jagexGetAccounts(maxCount).stream()
                .map(BwuGameLauncher::fromNative)
                .toList();
    }

    @Override public int jagexAccountCount()                                  { return client.jagexAccountCount(); }
    @Override public void jagexRemoveAccount(String uuid)                     { client.jagexRemoveAccount(uuid); }
    @Override public void jagexSelectCharacter(String uuid, int charIdx)      { client.jagexSelectCharacter(uuid, charIdx); }
    @Override public void jagexEnsureSession(String uuid)                     { client.jagexEnsureSession(uuid); }
    @Override public void jagexLaunch(String uuid)                            { client.jagexLaunch(uuid); }

    // ── Utility ────────────────────────────────────────────────────────────

    @Override public String generateUuid() { return client.generateUuid(); }

    @Override
    public void close() {
        client.close();
    }

    // ── Conversion helpers ─────────────────────────────────────────────────

    private static BwuAccount toNative(Account a) {
        return new BwuAccount(
                a.uuid(), a.name(), a.password(), a.pin(),
                a.worldA(), a.worldB(),
                BwuTargetType.valueOf(a.targetType().name()),
                BwuAccountType.valueOf(a.accountType().name()),
                a.autoLogin(), a.autoRestart());
    }

    private static Account fromNative(BwuAccount a) {
        return new Account(
                a.uuid(), a.name(), a.password(), a.pin(),
                a.worldA(), a.worldB(),
                TargetType.valueOf(a.targetType().name()),
                AccountType.valueOf(a.accountType().name()),
                a.autoLogin(), a.autoRestart());
    }

    private static BwuLoadParams toNative(LoadParams p) {
        return new BwuLoadParams(
                p.pin(), p.email(), p.password(), p.uuid(),
                p.worldA(), p.worldB(), p.autoLogin());
    }

    private static JagexAccount fromNative(BwuJagexAccount a) {
        List<JagexCharacter> chars = a.characters().stream()
                .map(c -> new JagexCharacter(c.accountId(), c.displayName(), c.userHash()))
                .toList();
        return new JagexAccount(
                a.uuid(), a.subject(), a.displayLabel(),
                chars, a.selectedCharacter(),
                a.sessionId(), a.sessionExpiresAt());
    }
}
