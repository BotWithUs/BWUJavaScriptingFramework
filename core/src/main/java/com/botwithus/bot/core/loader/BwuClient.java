package com.botwithus.bot.core.loader;

import com.botwithus.bot.core.util.Throwables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.lang.foreign.SymbolLookup.libraryLookup;
import static java.lang.foreign.ValueLayout.JAVA_INT;

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
            BwuNative bwuNative = new BwuNative(lookup);
            log.info("Loaded bwu.dll from {}", dllPath);
            return Optional.of(new BwuClient(bwuNative, arena));
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

    /**
     * Resolve bwu.dll using a three-stage strategy:
     * <ol>
     *   <li>{@code BWU_DLL_PATH} env var (dev override)</li>
     *   <li>Filesystem — DLL next to the application's own install directory
     *       (the code source), <em>not</em> the ambient working directory</li>
     *   <li>Bundled resource — extract {@code /native/bwu.dll} to a temp file</li>
     * </ol>
     *
     * <p>The loader is intentionally <em>not</em> resolved from the
     * downloadable native cache ({@code ~/.botwithus/native/}). bwu.dll is
     * the component that populates that cache, so it cannot be delivered by
     * it; it ships bundled in the JAR and updates with the application. A
     * stale bwu.dll left in the cache by an older build is ignored here.</p>
     *
     * @param resourceAnchor class whose classloader contains the bundled resource
     * @return path to the DLL, or {@code null} if unavailable
     */
    public static Path resolve(Class<?> resourceAnchor) {
        String envPath = System.getenv("BWU_DLL_PATH");
        if (envPath != null && !envPath.isBlank()) {
            Path devPath = Path.of(envPath);
            if (Files.isRegularFile(devPath)) {
                log.info("Using debug bwu.dll from BWU_DLL_PATH: {}", devPath);
                return devPath;
            }
            log.warn("BWU_DLL_PATH set but file not found: {}", devPath);
        }

        Path nextToApp = dllNextToCodeSource(resourceAnchor);
        if (nextToApp != null) {
            log.info("Using bwu.dll next to application install dir: {}", nextToApp);
            return nextToApp;
        }

        try (InputStream in = resourceAnchor.getResourceAsStream("/native/bwu.dll")) {
            if (in == null) {
                return null;
            }
            Path tmp = Files.createTempFile("bwu", ".dll");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Extracted bwu.dll from resources to {}", tmp);
            return tmp;
        } catch (IOException e) {
            log.warn("Failed to extract bwu.dll from resources: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Resolves {@code bwu.dll} sitting next to the application's own code
     * source (the directory the running JAR/classes were loaded from).
     * Deliberately does <em>not</em> consult the ambient working directory:
     * the loader runs native code in-process and performs auth + game
     * injection, so a {@code bwu.dll} planted in whatever folder the app was
     * launched from must never be picked up. Returns {@code null} when the
     * code source is unavailable or has no sibling DLL.
     */
    private static Path dllNextToCodeSource(Class<?> resourceAnchor) {
        try {
            var codeSource = resourceAnchor.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                return null;
            }
            URL location = codeSource.getLocation();
            if (location == null) {
                return null;
            }
            Path codePath = Paths.get(location.toURI());
            Path baseDir = Files.isDirectory(codePath) ? codePath : codePath.getParent();
            if (baseDir == null) {
                return null;
            }
            Path candidate = baseDir.resolve("bwu.dll");
            return Files.isRegularFile(candidate) ? candidate : null;
        } catch (URISyntaxException | RuntimeException e) {
            log.debug("could not resolve bwu.dll next to code source: {}", e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    public void init() {
        check(callInt(n.bwuInit));
        initialized = true;
    }

    public void shutdown() {
        if (initialized) {
            callVoid(n.bwuShutdown);
            initialized = false;
        }
    }

    public BwuStatus getStatus() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(BwuLayouts.BWU_STATUS);
            check(callInt(n.bwuGetStatus, out));
            return BwuStatus.read(out);
        }
    }

    public String getLastError() {
        return readReturnedString(callPtr(n.bwuGetLastError));
    }

    public String getVersion() {
        return readReturnedString(callPtr(n.bwuGetVersion));
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
        check(callInt(n.bwuLogin));
    }

    public void loginWithToken(String token) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwuLoginWithToken, arena.allocateFrom(token)));
        }
    }

    public boolean isLoggedIn() {
        return callInt(n.bwuIsLoggedIn) != 0;
    }

    public String getToken() {
        return readReturnedString(callPtr(n.bwuGetToken));
    }

    public BwuUser getUser() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(BwuLayouts.BWU_USER);
            check(callInt(n.bwuGetUser, out));
            return BwuUser.read(out);
        }
    }

    public void logout() {
        callVoid(n.bwuLogout);
    }

    public void saveToken(Path path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathSeg = (path != null)
                    ? arena.allocateFrom(path.toString())
                    : MemorySegment.NULL;
            check(callInt(n.bwuSaveToken, pathSeg));
        }
    }

    public void loadToken(Path path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathSeg = (path != null)
                    ? arena.allocateFrom(path.toString())
                    : MemorySegment.NULL;
            check(callInt(n.bwuLoadToken, pathSeg));
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
        check(callInt(n.bwuRefreshModule));
    }

    /**
     * Whether the loaded DLL is a dev build that supports local module loading.
     * Returns {@code false} for production (Release) builds.
     */
    public boolean isDevBuild() {
        return n.bwuLoadLocalModule != null;
    }

    /**
     * Returns the value of the {@code BWU_LOCAL_MODULE} environment variable,
     * or {@code null} if it is not set. When set and the DLL is a dev build,
     * the download phase will automatically load from this path instead of
     * fetching from the server.
     */
    public static String getLocalModuleEnvPath() {
        String val = System.getenv("BWU_LOCAL_MODULE");
        return (val != null && !val.isBlank()) ? val : null;
    }

    /**
     * Clear {@code BWU_LOCAL_MODULE} from the process environment so the dev
     * DLL's download phase falls through to the prod server instead of
     * auto-loading a local module. Used by the loader screen's "Test prod
     * build" toggle.
     *
     * <p>Goes through {@code kernel32!SetEnvironmentVariableW} so the change
     * is visible to native code in the same process — {@link System#getenv}
     * keeps returning the original value (it's snapshot at JVM start), but
     * any C code reading via {@code GetEnvironmentVariableW} / {@code getenv}
     * after this call will see the variable as unset.</p>
     *
     * <p>Idempotent. Returns {@code true} on success, {@code false} if the
     * Win32 call failed (caller can decide whether to surface the error;
     * a failure here just means the dev DLL will keep its existing behavior).</p>
     */
    public static boolean clearLocalModuleEnvPath() {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment name = toWideString(scratch, "BWU_LOCAL_MODULE");
            int rv = (int) SET_ENVIRONMENT_VARIABLE_W.invokeExact(name, MemorySegment.NULL);
            if (rv == 0) {
                log.warn("SetEnvironmentVariableW(BWU_LOCAL_MODULE, NULL) failed");
                return false;
            }
            log.info("Cleared BWU_LOCAL_MODULE from process environment (force prod build)");
            return true;
        } catch (Throwable t) {
            log.warn("Failed to clear BWU_LOCAL_MODULE: {}", t.getMessage());
            return false;
        }
    }

    /**
     * Load the agent module from a local file instead of downloading from the
     * server. Only available in dev (Debug) builds of the native DLL — check
     * {@link #isDevBuild()} first.
     *
     * @param path absolute path to the DLL file on disk
     * @throws UnsupportedOperationException if the DLL is a production build
     */
    public void loadLocalModule(Path path) {
        if (n.bwuLoadLocalModule == null) {
            throw new UnsupportedOperationException(
                    "loadLocalModule is only available in dev builds of bwu.dll");
        }
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwuLoadLocalModule, arena.allocateFrom(path.toString())));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Account Management (Classic)
    // ═══════════════════════════════════════════════════════════════════════

    public void addAccount(BwuAccount account) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwuAddAccount, account.writeTo(arena)));
        }
    }

    public void removeAccount(String uuid) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwuRemoveAccount, arena.allocateFrom(uuid)));
        }
    }

    public int getAccountCount() {
        return callInt(n.bwuGetAccountCount);
    }

    public BwuAccount getAccount(int index) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(BwuLayouts.BWU_ACCOUNT);
            check(callIntIS(n.bwuGetAccount, index, out));
            return BwuAccount.read(out);
        }
    }

    public BwuAccount findAccount(String uuid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(BwuLayouts.BWU_ACCOUNT);
            check(callInt2(n.bwuFindAccount, arena.allocateFrom(uuid), out));
            return BwuAccount.read(out);
        }
    }

    public void updateAccount(BwuAccount account) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwuUpdateAccount, account.writeTo(arena)));
        }
    }

    public void clearAccounts() {
        callVoid(n.bwuClearAccounts);
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
            check(callInt(n.bwuLaunchDefault, arena.allocateFrom(accountUuid)));
        }
    }

    /**
     * Launch the game client via Steam protocol URL. Non-blocking.
     *
     * @param accountUuid UUID of the BwuAccount providing injection credentials
     */
    public void launchPlatform(String accountUuid) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwuLaunchPlatform, arena.allocateFrom(accountUuid)));
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
            check(callInt2(n.bwuLaunchManaged, nameSeg, arena.allocateFrom(accountUuid)));
        }
    }

    public void setProviderPath(Path path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathSeg = (path != null)
                    ? arena.allocateFrom(path.toString())
                    : MemorySegment.NULL;
            check(callInt(n.bwuSetProviderPath, pathSeg));
        }
    }

    public String getProviderPath() {
        return readReturnedString(callPtr(n.bwuGetProviderPath));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Provider Account Discovery
    // ═══════════════════════════════════════════════════════════════════════

    public void refreshProviderAccounts() {
        check(callInt(n.bwuRefreshProviderAccounts));
    }

    public List<BwuProviderAccount> getProviderAccounts(int maxCount) {
        try (Arena arena = Arena.ofConfined()) {
            long elemSize = BwuLayouts.BWU_PROVIDER_ACCOUNT.byteSize();
            MemorySegment buf = arena.allocate(elemSize * maxCount);
            MemorySegment outCount = arena.allocate(JAVA_INT);
            check(callIntSIS(n.bwuGetProviderAccounts, buf, maxCount, outCount));
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
            check(callInt(n.bwuJagexLogin, out));
            return BwuJagexAccount.read(out);
        }
    }

    public BwuJagexAccount jagexGetAccount(String uuid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(BwuLayouts.BWU_JAGEX_ACCOUNT);
            check(callInt2(n.bwuJagexGetAccount, arena.allocateFrom(uuid), out));
            return BwuJagexAccount.read(out);
        }
    }

    public List<BwuJagexAccount> jagexGetAccounts(int maxCount) {
        try (Arena arena = Arena.ofConfined()) {
            long elemSize = BwuLayouts.BWU_JAGEX_ACCOUNT.byteSize();
            MemorySegment buf = arena.allocate(elemSize * maxCount);
            MemorySegment outCount = arena.allocate(JAVA_INT);
            check(callIntSIS(n.bwuJagexGetAccounts, buf, maxCount, outCount));
            int count = outCount.get(JAVA_INT, 0);
            List<BwuJagexAccount> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                result.add(BwuJagexAccount.read(buf.asSlice(i * elemSize, elemSize)));
            }
            return result;
        }
    }

    public int jagexAccountCount() {
        return callInt(n.bwuJagexAccountCount);
    }

    public void jagexRemoveAccount(String uuid) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwuJagexRemoveAccount, arena.allocateFrom(uuid)));
        }
    }

    /**
     * Restore previously authenticated Jagex accounts from the Windows Credential Manager.
     * Refreshes OAuth tokens and rebuilds sessions for each saved account.
     * <strong>Blocks</strong> while making network requests.
     */
    public void jagexRestoreAccounts() {
        check(callInt(n.bwuJagexRestoreAccounts));
    }

    /**
     * Re-fetch the character list for a Jagex account using its current session.
     * Requires a valid session — call {@link #jagexEnsureSession} first.
     *
     * @param uuid Jagex account UUID
     */
    public void jagexRefreshCharacters(String uuid) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwuJagexRefreshCharacters, arena.allocateFrom(uuid)));
        }
    }

    public void jagexSelectCharacter(String uuid, int characterIndex) {
        try (Arena arena = Arena.ofConfined()) {
            check(callIntSI(n.bwuJagexSelectCharacter, arena.allocateFrom(uuid), characterIndex));
        }
    }

    public void jagexEnsureSession(String uuid) {
        try (Arena arena = Arena.ofConfined()) {
            check(callInt(n.bwuJagexEnsureSession, arena.allocateFrom(uuid)));
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
            check(callIntSSI(n.bwuJagexLaunch,
                    arena.allocateFrom(jagexUuid), acctSeg, characterIndex));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Utility
    // ═══════════════════════════════════════════════════════════════════════

    public String generateUuid() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(BwuLayouts.UUID_LEN);
            check(callIntSI(n.bwuGenerateUuid, buf, BwuLayouts.UUID_LEN));
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

    /** Upper bound on the length of any string returned by the loader native side. */
    private static final long MAX_RETURNED_STRING_BYTES = 4096L;

    private static String readReturnedString(MemorySegment seg) {
        if (seg.equals(MemorySegment.NULL)) {
            return null;
        }
        return seg.reinterpret(MAX_RETURNED_STRING_BYTES).getString(0);
    }

    // ── MethodHandle invocation wrappers ───────────────────────────────────
    // invokeExact is signature-polymorphic: the return type at the call site
    // must match the MethodHandle's type exactly. Each native return-type /
    // parameter combination therefore needs its own wrapper.

    /** () -> int */
    private static int callInt(MethodHandle mh) {
        try { return (int) mh.invokeExact(); } catch (Throwable t) { throw rethrow(t); }
    }

    /** (MemorySegment) -> int */
    private static int callInt(MethodHandle mh, MemorySegment a0) {
        try { return (int) mh.invokeExact(a0); } catch (Throwable t) { throw rethrow(t); }
    }

    /** (MemorySegment, MemorySegment) -> int */
    private static int callInt2(MethodHandle mh, MemorySegment a0, MemorySegment a1) {
        try { return (int) mh.invokeExact(a0, a1); } catch (Throwable t) { throw rethrow(t); }
    }

    /** (int, MemorySegment) -> int */
    private static int callIntIS(MethodHandle mh, int a0, MemorySegment a1) {
        try { return (int) mh.invokeExact(a0, a1); } catch (Throwable t) { throw rethrow(t); }
    }

    /** (MemorySegment, int) -> int */
    private static int callIntSI(MethodHandle mh, MemorySegment a0, int a1) {
        try { return (int) mh.invokeExact(a0, a1); } catch (Throwable t) { throw rethrow(t); }
    }

    /** (MemorySegment, int, MemorySegment) -> int */
    private static int callIntSIS(MethodHandle mh, MemorySegment a0, int a1, MemorySegment a2) {
        try { return (int) mh.invokeExact(a0, a1, a2); } catch (Throwable t) { throw rethrow(t); }
    }

    /** (MemorySegment, MemorySegment, int) -> int */
    private static int callIntSSI(MethodHandle mh, MemorySegment a0, MemorySegment a1, int a2) {
        try { return (int) mh.invokeExact(a0, a1, a2); } catch (Throwable t) { throw rethrow(t); }
    }

    /** () -> MemorySegment (for const char* / pointer returns) */
    private static MemorySegment callPtr(MethodHandle mh) {
        try { return (MemorySegment) mh.invokeExact(); } catch (Throwable t) { throw rethrow(t); }
    }

    /** () -> void */
    private static void callVoid(MethodHandle mh) {
        try { mh.invokeExact(); } catch (Throwable t) { throw rethrow(t); }
    }

    private static RuntimeException rethrow(Throwable t) {
        return Throwables.rethrow(t, cause -> new BwuException("Native call failed", cause));
    }

    // ─── kernel32 binding: SetEnvironmentVariableW ────────────────────────
    // Used by clearLocalModuleEnvPath(). Kept local to BwuClient because the
    // only consumer is the env var contract this class already owns.

    private static final SymbolLookup KERNEL32 =
            SymbolLookup.libraryLookup("kernel32", Arena.global());

    /** {@code BOOL SetEnvironmentVariableW(LPCWSTR name, LPCWSTR value);} — value=NULL deletes. */
    private static final MethodHandle SET_ENVIRONMENT_VARIABLE_W = Linker.nativeLinker().downcallHandle(
            KERNEL32.find("SetEnvironmentVariableW").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS));

    private static MemorySegment toWideString(Arena arena, String name) {
        byte[] utf16 = name.getBytes(StandardCharsets.UTF_16LE);
        MemorySegment seg = arena.allocate(utf16.length + 2L);
        MemorySegment.copy(utf16, 0, seg, ValueLayout.JAVA_BYTE, 0, utf16.length);
        seg.set(ValueLayout.JAVA_BYTE, utf16.length,     (byte) 0);
        seg.set(ValueLayout.JAVA_BYTE, utf16.length + 1, (byte) 0);
        return seg;
    }
}
