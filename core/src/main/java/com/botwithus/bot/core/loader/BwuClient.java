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
 *         bwu.login();
 *         // ...
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
     * Download the agent module. Requires login. <strong>Blocks</strong> until complete.
     */
    public void downloadModule() {
        check(callInt(n.bwu_download_module));
    }

    public boolean hasModule() {
        return callInt(n.bwu_has_module) != 0;
    }

    /**
     * Get a copy of the downloaded module bytes.
     */
    public byte[] getModuleBytes() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pData = arena.allocate(ADDRESS);
            MemorySegment pSize = arena.allocate(JAVA_INT);
            check(callInt2(n.bwu_get_module_bytes, pData, pSize));

            MemorySegment dataPtr = pData.get(ADDRESS, 0);
            int size = pSize.get(JAVA_INT, 0);
            return dataPtr.reinterpret(size).toArray(JAVA_BYTE);
        }
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
    //  Process Management
    // ═══════════════════════════════════════════════════════════════════════

    public void launchDefault() {
        check(callInt(n.bwu_launch_default));
    }

    public void launchPlatform() {
        check(callInt(n.bwu_launch_platform));
    }

    public void launchManaged(String accountName) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nameSeg = (accountName != null && !accountName.isEmpty())
                    ? arena.allocateFrom(accountName)
                    : MemorySegment.NULL;
            check(callInt(n.bwu_launch_managed, nameSeg));
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

    /**
     * Find all running game client processes (rs2client.exe / RuneScape.exe).
     *
     * @param maxCount maximum PIDs to return
     * @return array of process IDs
     */
    public int[] findProcesses(int maxCount) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pids = arena.allocate(JAVA_INT, maxCount);
            MemorySegment outCount = arena.allocate(JAVA_INT);
            check(callIntSIS(n.bwu_find_processes, pids, maxCount, outCount));
            int count = outCount.get(JAVA_INT, 0);
            int[] result = new int[count];
            for (int i = 0; i < count; i++) {
                result[i] = pids.get(JAVA_INT, (long) i * JAVA_INT.byteSize());
            }
            return result;
        }
    }

    /**
     * Poll for the next process event. Non-blocking.
     *
     * @return the event, or empty if the queue is empty
     */
    public Optional<BwuProcessEvent> pollProcessEvent() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(BwuLayouts.BWU_PROCESS_EVENT);
            int rc = callInt(n.bwu_poll_process_event, out);
            if (rc == BwuError.NOT_FOUND.code()) {
                return Optional.empty();
            }
            check(rc);
            return Optional.of(BwuProcessEvent.read(out));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Module Loading
    // ═══════════════════════════════════════════════════════════════════════

    public void loadModule(int pid, BwuLoadParams params) {
        try (Arena arena = Arena.ofConfined()) {
            check(callIntIS(n.bwu_load_module, pid, params.writeTo(arena)));
        }
    }

    public void loadModuleRaw(int pid, byte[] data, BwuLoadParams params) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dataSeg = arena.allocateFrom(JAVA_BYTE, data);
            check(callIntISIS(n.bwu_load_module_raw, pid, dataSeg, data.length, params.writeTo(arena)));
        }
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

    public void jagexLaunch(String uuid) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwu_jagex_launch, arena.allocateFrom(uuid)));
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

    /** (int, MemorySegment, int, MemorySegment) -> int */
    private static int callIntISIS(MethodHandle mh, int a0, MemorySegment a1, int a2, MemorySegment a3) {
        try { return (int) mh.invokeExact(a0, a1, a2, a3); }
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
