package com.botwithus.bot.core.loader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.lang.foreign.SymbolLookup.libraryLookup;
import static java.lang.foreign.ValueLayout.*;

/**
 * High-level Java API for the BotWithUs loader DLL ({@code bwu.dll}).
 * <p>
 * The DLL is loaded optionally via Project Panama. If the DLL is not present
 * or native access is not enabled, {@link #load} returns {@link Optional#empty()}.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * Optional<BwuClient> client = BwuClient.load(Path.of("bwu.dll"));
 * if (client.isPresent()) {
 *     try (BwuClient bwu = client.get()) {
 *         bwu.init();
 *         bwu.login();           // auto-starts module download
 *         bwu.addAccount(account);
 *         bwu.launchDefault(accountUuid);  // background: launch + inject
 *     }
 * }
 * }</pre>
 *
 * <h3>Runtime requirement</h3>
 * The JVM must be started with {@code --enable-native-access=com.botwithus.bot.core}
 * (or {@code ALL-UNNAMED} for classpath usage).
 */
public final class BwuClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BwuClient.class);

    private final BwuNative n;
    private final Arena libraryArena;
    private boolean initialized;

    private BwuClient(BwuNative n, Arena libraryArena) {
        this.n = n;
        this.libraryArena = libraryArena;
    }

    /**
     * Attempt to load {@code bwu.dll} from the given path.
     *
     * @return the client, or empty if the DLL is absent or native access is unavailable
     */
    public static Optional<BwuClient> load(Path dllPath) {
        if (!Files.isRegularFile(dllPath)) {
            log.debug("bwu.dll not found at {}", dllPath);
            return Optional.empty();
        }
        try {
            Arena arena = Arena.ofShared();
            var lookup = libraryLookup(dllPath, arena);
            BwuNative native_ = new BwuNative(lookup);
            log.info("Loaded bwu.dll from {}", dllPath);
            return Optional.of(new BwuClient(native_, arena));
        } catch (IllegalCallerException e) {
            log.warn("Native access not enabled for bwu.dll — add --enable-native-access=com.botwithus.bot.core");
            return Optional.empty();
        } catch (UnsatisfiedLinkError e) {
            log.warn("Failed to load bwu.dll: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Check whether a DLL exists at the given path without loading it.
     */
    public static boolean isAvailable(Path dllPath) {
        return Files.isRegularFile(dllPath);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    public void init() {
        check(callInt(n.bwu_init));
        initialized = true;
    }

    public void shutdown() {
        if (initialized) {
            callVoid(n.bwu_shutdown);
            initialized = false;
        }
    }

    public BwuStatus getStatus() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(BwuLayouts.BWU_STATUS);
            check(callInt(n.bwu_get_status, out));
            return BwuStatus.read(out);
        }
    }

    public String getLastError() {
        return readReturnedString(callPtr(n.bwu_get_last_error));
    }

    public String getVersion() {
        return readReturnedString(callPtr(n.bwu_get_version));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Authentication (BotWithUs)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Start the BotWithUs SSO login flow. Opens the default browser and
     * <strong>blocks</strong> until callback or timeout (5 min).
     * On success, automatically begins downloading the module in the background.
     */
    public void login() {
        check(callInt(n.bwu_login));
    }

    public void loginWithToken(String token) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwu_login_with_token, arena.allocateFrom(token)));
        }
    }

    public boolean isLoggedIn() {
        return callInt(n.bwu_is_logged_in) != 0;
    }

    public String getToken() {
        return readReturnedString(callPtr(n.bwu_get_token));
    }

    public BwuUser getUser() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(BwuLayouts.BWU_USER);
            check(callInt(n.bwu_get_user, out));
            return BwuUser.read(out);
        }
    }

    public void logout() {
        callVoid(n.bwu_logout);
    }

    public void saveToken(Path path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathSeg = (path != null)
                    ? arena.allocateFrom(path.toString())
                    : MemorySegment.NULL;
            check(callInt(n.bwu_save_token, pathSeg));
        }
    }

    public void loadToken(Path path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathSeg = (path != null)
                    ? arena.allocateFrom(path.toString())
                    : MemorySegment.NULL;
            check(callInt(n.bwu_load_token, pathSeg));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Module Management
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Trigger a module refresh. Checks the server for a newer version and
     * downloads it if the checksum differs. Runs in a background thread;
     * track progress via {@link #getStatus()}.
     */
    public void refreshModule() {
        check(callInt(n.bwu_refresh_module));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Account Management (Classic)
    // ═══════════════════════════════════════════════════════════════════════

    public void addAccount(BwuAccount account) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwu_add_account, account.writeTo(arena)));
        }
    }

    public void removeAccount(String uuid) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwu_remove_account, arena.allocateFrom(uuid)));
        }
    }

    public int getAccountCount() {
        return callInt(n.bwu_get_account_count);
    }

    public BwuAccount getAccount(int index) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(BwuLayouts.BWU_ACCOUNT);
            check(callIntIS(n.bwu_get_account, index, out));
            return BwuAccount.read(out);
        }
    }

    public BwuAccount findAccount(String uuid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(BwuLayouts.BWU_ACCOUNT);
            check(callInt2(n.bwu_find_account, arena.allocateFrom(uuid), out));
            return BwuAccount.read(out);
        }
    }

    public void updateAccount(BwuAccount account) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwu_update_account, account.writeTo(arena)));
        }
    }

    public void clearAccounts() {
        callVoid(n.bwu_clear_accounts);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Launch (Non-blocking Triggers)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Launch the game client via direct executable. Non-blocking — runs in a
     * background thread that handles launch, process detection, credential
     * writing, and module injection.
     *
     * @param accountUuid UUID of the BwuAccount providing injection credentials
     */
    public void launchDefault(String accountUuid) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwu_launch_default, arena.allocateFrom(accountUuid)));
        }
    }

    /**
     * Launch the game client via Steam protocol URL. Non-blocking.
     *
     * @param accountUuid UUID of the BwuAccount providing injection credentials
     */
    public void launchPlatform(String accountUuid) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwu_launch_platform, arena.allocateFrom(accountUuid)));
        }
    }

    /**
     * Launch the game client through the Jagex Launcher CLI. Non-blocking.
     *
     * @param accountName launcher account name, or {@code null} for default
     * @param accountUuid UUID of the BwuAccount providing injection credentials
     */
    public void launchManaged(String accountName, String accountUuid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nameSeg = (accountName != null && !accountName.isEmpty())
                    ? arena.allocateFrom(accountName)
                    : MemorySegment.NULL;
            check(callInt2(n.bwu_launch_managed, nameSeg, arena.allocateFrom(accountUuid)));
        }
    }

    public void setProviderPath(Path path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathSeg = (path != null)
                    ? arena.allocateFrom(path.toString())
                    : MemorySegment.NULL;
            check(callInt(n.bwu_set_provider_path, pathSeg));
        }
    }

    public String getProviderPath() {
        return readReturnedString(callPtr(n.bwu_get_provider_path));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Provider Account Discovery
    // ═══════════════════════════════════════════════════════════════════════

    public void refreshProviderAccounts() {
        check(callInt(n.bwu_refresh_provider_accounts));
    }

    public List<BwuProviderAccount> getProviderAccounts(int maxCount) {
        try (Arena arena = Arena.ofConfined()) {
            long elemSize = BwuLayouts.BWU_PROVIDER_ACCOUNT.byteSize();
            MemorySegment buf = arena.allocate(elemSize * maxCount);
            MemorySegment outCount = arena.allocate(JAVA_INT);
            check(callIntSIS(n.bwu_get_provider_accounts, buf, maxCount, outCount));
            int count = outCount.get(JAVA_INT, 0);
            List<BwuProviderAccount> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                result.add(BwuProviderAccount.read(buf.asSlice(i * elemSize, elemSize)));
            }
            return result;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Jagex Account Authentication
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Authenticate a new Jagex account via OAuth browser flow.
     * <strong>Blocks</strong> until callback or timeout (5 min).
     */
    public BwuJagexAccount jagexLogin() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(BwuLayouts.BWU_JAGEX_ACCOUNT);
            check(callInt(n.bwu_jagex_login, out));
            return BwuJagexAccount.read(out);
        }
    }

    public BwuJagexAccount jagexGetAccount(String uuid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(BwuLayouts.BWU_JAGEX_ACCOUNT);
            check(callInt2(n.bwu_jagex_get_account, arena.allocateFrom(uuid), out));
            return BwuJagexAccount.read(out);
        }
    }

    public List<BwuJagexAccount> jagexGetAccounts(int maxCount) {
        try (Arena arena = Arena.ofConfined()) {
            long elemSize = BwuLayouts.BWU_JAGEX_ACCOUNT.byteSize();
            MemorySegment buf = arena.allocate(elemSize * maxCount);
            MemorySegment outCount = arena.allocate(JAVA_INT);
            check(callIntSIS(n.bwu_jagex_get_accounts, buf, maxCount, outCount));
            int count = outCount.get(JAVA_INT, 0);
            List<BwuJagexAccount> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                result.add(BwuJagexAccount.read(buf.asSlice(i * elemSize, elemSize)));
            }
            return result;
        }
    }

    public int jagexAccountCount() {
        return callInt(n.bwu_jagex_account_count);
    }

    public void jagexRemoveAccount(String uuid) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwu_jagex_remove_account, arena.allocateFrom(uuid)));
        }
    }

    /**
     * Restore previously authenticated Jagex accounts from the Windows Credential Manager.
     * Refreshes OAuth tokens and rebuilds sessions for each saved account.
     * <strong>Blocks</strong> while making network requests.
     */
    public void jagexRestoreAccounts() {
        check(callInt(n.bwu_jagex_restore_accounts));
    }

    /**
     * Re-fetch the character list for a Jagex account using its current session.
     * Requires a valid session — call {@link #jagexEnsureSession} first.
     *
     * @param uuid Jagex account UUID
     */
    public void jagexRefreshCharacters(String uuid) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwu_jagex_refresh_characters, arena.allocateFrom(uuid)));
        }
    }

    public void jagexSelectCharacter(String uuid, int characterIndex) {
        try (Arena arena = Arena.ofConfined()) {
            check(callIntSI(n.bwu_jagex_select_character, arena.allocateFrom(uuid), characterIndex));
        }
    }

    public void jagexEnsureSession(String uuid) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwu_jagex_ensure_session, arena.allocateFrom(uuid)));
        }
    }

    /**
     * Launch rs2client.exe using a Jagex account's active session and inject the module.
     * Non-blocking — runs in a background thread.
     *
     * @param jagexUuid      Jagex account UUID (for session/character)
     * @param accountUuid    BwuAccount UUID (for injection credentials)
     * @param characterIndex zero-based index into the account's character list
     */
    public void jagexLaunch(String jagexUuid, String accountUuid, int characterIndex) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment acctSeg = (accountUuid != null && !accountUuid.isEmpty())
                    ? arena.allocateFrom(accountUuid)
                    : MemorySegment.NULL;
            check(callIntSSI(n.bwu_jagex_launch,
                    arena.allocateFrom(jagexUuid), acctSeg, characterIndex));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Utility
    // ═══════════════════════════════════════════════════════════════════════

    public String generateUuid() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(BwuLayouts.UUID_LEN);
            check(callIntSI(n.bwu_generate_uuid, buf, BwuLayouts.UUID_LEN));
            return buf.getString(0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  AutoCloseable
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void close() {
        shutdown();
        libraryArena.close();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Internal helpers
    // ═══════════════════════════════════════════════════════════════════════

    private void check(int rc) {
        if (rc != BwuError.OK.code()) {
            BwuError err = BwuError.fromCode(rc);
            if (err == null) {
                throw new BwuException(BwuError.NOT_FOUND, "Unknown error code: " + rc);
            }
            String nativeMsg = getLastError();
            throw new BwuException(err, nativeMsg);
        }
    }

    private static String readReturnedString(MemorySegment seg) {
        if (seg.equals(MemorySegment.NULL)) {
            return null;
        }
        return seg.reinterpret(4096).getString(0);
    }

    // ── MethodHandle invocation wrappers ───────────────────────────────────
    // invokeExact is signature-polymorphic: the return type at the call site
    // must match the MethodHandle's type exactly. Each native return-type /
    // parameter combination therefore needs its own wrapper.

    /** () -> int */
    private static int callInt(MethodHandle mh) {
        try { return (int) mh.invokeExact(); }
        catch (Throwable t) { throw rethrow(t); }
    }

    /** (MemorySegment) -> int */
    private static int callInt(MethodHandle mh, MemorySegment a0) {
        try { return (int) mh.invokeExact(a0); }
        catch (Throwable t) { throw rethrow(t); }
    }

    /** (MemorySegment, MemorySegment) -> int */
    private static int callInt2(MethodHandle mh, MemorySegment a0, MemorySegment a1) {
        try { return (int) mh.invokeExact(a0, a1); }
        catch (Throwable t) { throw rethrow(t); }
    }

    /** (int, MemorySegment) -> int */
    private static int callIntIS(MethodHandle mh, int a0, MemorySegment a1) {
        try { return (int) mh.invokeExact(a0, a1); }
        catch (Throwable t) { throw rethrow(t); }
    }

    /** (MemorySegment, int) -> int */
    private static int callIntSI(MethodHandle mh, MemorySegment a0, int a1) {
        try { return (int) mh.invokeExact(a0, a1); }
        catch (Throwable t) { throw rethrow(t); }
    }

    /** (MemorySegment, int, MemorySegment) -> int */
    private static int callIntSIS(MethodHandle mh, MemorySegment a0, int a1, MemorySegment a2) {
        try { return (int) mh.invokeExact(a0, a1, a2); }
        catch (Throwable t) { throw rethrow(t); }
    }

    /** (MemorySegment, MemorySegment, int) -> int */
    private static int callIntSSI(MethodHandle mh, MemorySegment a0, MemorySegment a1, int a2) {
        try { return (int) mh.invokeExact(a0, a1, a2); }
        catch (Throwable t) { throw rethrow(t); }
    }

    /** () -> MemorySegment (for const char* / pointer returns) */
    private static MemorySegment callPtr(MethodHandle mh) {
        try { return (MemorySegment) mh.invokeExact(); }
        catch (Throwable t) { throw rethrow(t); }
    }

    /** () -> void */
    private static void callVoid(MethodHandle mh) {
        try { mh.invokeExact(); }
        catch (Throwable t) { throw rethrow(t); }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        if (t instanceof Error e) throw e;
        return new RuntimeException("Native call failed", t);
    }
}
