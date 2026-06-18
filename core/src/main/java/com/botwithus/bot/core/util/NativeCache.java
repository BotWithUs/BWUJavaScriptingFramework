package com.botwithus.bot.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the on-disk location of cached native artifacts. The
 * canonical cache root is {@code $USER_HOME/.botwithus/native/}, matching
 * the convention used by other persistent stores in this codebase
 * (credentials, repository config, trusted PGP keys).
 *
 * <p>Construction is side-effect free; {@link #ensureExists()} creates
 * the directory on demand. Callers that only read the path can use
 * {@link #cacheDir()} or {@link #resolve(String)} directly without
 * creating the directory; callers that intend to write into it should
 * call {@link #ensureExists()} first.</p>
 *
 * <p>The cache directory is populated by a separate loader, out-of-band
 * from the host process. The host only consumes — for dev work, point
 * the matching {@code -D…} override at a build directory to bypass the
 * cache entry entirely.</p>
 */
public final class NativeCache {

    private static final String CONFIG_DIR_NAME = ".botwithus";
    private static final String NATIVE_SUBDIR = "native";

    private static final Logger log = LoggerFactory.getLogger(NativeCache.class);
    /** Integrity-verification mode property: {@code warn} (default) logs, {@code enforce} throws. */
    private static final String VERIFY_PROP = "botwithus.native.verify";
    private static final String VERIFY_ENFORCE = "enforce";
    private static final String SHA256_SIDECAR_SUFFIX = ".sha256";

    /** File name of the NXT cache decoder library within the native cache. */
    public static final String NXTCACHE_DLL_NAME = "NXTCache.dll";

    /** File name of the WorldWalker pathfinding library within the native cache. */
    public static final String WORLDWALKER_DLL_NAME = "worldwalker.dll";

    /** File name of the WorldWalker baked artifact within the native cache. */
    public static final String WORLDWALKER_ARTIFACT_NAME = "worldwalker.wwa";

    /** Editable global-teleport datasets within the native cache (scripter-editable). */
    public static final String WORLDWALKER_SPELL_TELEPORTS_NAME = "spell_teleports.json";
    public static final String WORLDWALKER_ITEM_TELEPORTS_NAME = "item_teleports.json";

    private final Path cacheDir;

    /** Cache rooted at the user's home directory ({@code ~/.botwithus/native/}). */
    public NativeCache() {
        this(Path.of(System.getProperty("user.home")).resolve(CONFIG_DIR_NAME).resolve(NATIVE_SUBDIR));
    }

    /** Cache rooted at an explicit directory; intended for tests. */
    public NativeCache(Path cacheDir) {
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
    }

    /** Absolute path of the cache directory. Not guaranteed to exist; see {@link #ensureExists()}. */
    public Path cacheDir() {
        return cacheDir;
    }

    /** Path of an entry within the cache directory. Existence is not checked. */
    public Path resolve(String filename) {
        Objects.requireNonNull(filename, "filename");
        return cacheDir.resolve(filename);
    }

    /** Create the cache directory if it does not yet exist. */
    public Path ensureExists() throws IOException {
        Files.createDirectories(cacheDir);
        return cacheDir;
    }

    /**
     * Locate {@code NXTCache.dll} on disk without loading it, using the same
     * precedence as {@code core.cache.NXTCache} at link time: the
     * {@code -Dnxtcache.dll} override first (when it points at an existing
     * file), then the cache entry under the default cache root. Returns empty
     * when neither is present.
     *
     * <p>Unlike {@code NXTCache}'s own resolver this neither links the DLL nor
     * throws when it is absent, so UI code can poll it as a readiness gate
     * before triggering the eager link.</p>
     */
    public static Optional<Path> locateNxtCacheDll() {
        String override = System.getProperty("nxtcache.dll");
        if (override != null && !override.isBlank()) {
            Path overridePath = Path.of(override);
            if (Files.isRegularFile(overridePath)) {
                return Optional.of(overridePath);
            }
        }
        Path cached = new NativeCache().resolve(NXTCACHE_DLL_NAME);
        return Files.isRegularFile(cached) ? Optional.of(cached) : Optional.empty();
    }

    /**
     * Locate {@code worldwalker.dll} on disk without loading it. Same precedence
     * as {@link #locateNxtCacheDll()}: the {@code -Dworldwalker.dll} override
     * first (when it points at an existing file), then the cache entry under
     * the default cache root. Returns empty when neither is present.
     */
    public static Optional<Path> locateWorldWalkerDll() {
        String override = System.getProperty("worldwalker.dll");
        if (override != null && !override.isBlank()) {
            Path overridePath = Path.of(override);
            if (Files.isRegularFile(overridePath)) {
                return Optional.of(overridePath);
            }
        }
        Path cached = new NativeCache().resolve(WORLDWALKER_DLL_NAME);
        return Files.isRegularFile(cached) ? Optional.of(cached) : Optional.empty();
    }

    /**
     * Locate the WorldWalker baked artifact on disk without loading it. Same
     * precedence as {@link #locateWorldWalkerDll()}: the
     * {@code -Dworldwalker.artifact} override first (when it points at an
     * existing file), then the cache entry under the default cache root.
     * Returns empty when neither is present.
     */
    public static Optional<Path> locateWorldWalkerArtifact() {
        String override = System.getProperty("worldwalker.artifact");
        if (override != null && !override.isBlank()) {
            Path overridePath = Path.of(override);
            if (Files.isRegularFile(overridePath)) {
                return Optional.of(overridePath);
            }
        }
        Path cached = new NativeCache().resolve(WORLDWALKER_ARTIFACT_NAME);
        return Files.isRegularFile(cached) ? Optional.of(cached) : Optional.empty();
    }

    /**
     * Locate the directory holding the editable WorldWalker teleport datasets
     * ({@code spell_teleports.json}, {@code item_teleports.json}). The
     * {@code -Dworldwalker.teleports} override wins when it points at an existing
     * directory; otherwise the default native-cache root (where
     * {@code worldwalker.dll} lives). Always returns a path — the native loader
     * skips any dataset file that is absent, so a missing file is not an error.
     */
    public static Path locateTeleportsDir() {
        String override = System.getProperty("worldwalker.teleports");
        if (override != null && !override.isBlank()) {
            Path overridePath = Path.of(override);
            if (Files.isDirectory(overridePath)) {
                return overridePath;
            }
        }
        return new NativeCache().cacheDir();
    }

    /**
     * Best-effort integrity gate, called immediately before a native DLL is
     * mapped and executed. The native dir is populated out-of-band by the
     * launcher; this canonicalizes the resolved path (resolving symlinks) and,
     * when the launcher has published a {@code <name>.sha256} sidecar next to
     * the DLL, verifies the file's SHA-256 against it. Mode via
     * {@code -Dbotwithus.native.verify}: {@code warn} (default) logs a
     * mismatch/absence and proceeds — a phased rollout that stays warn-only
     * until the launcher ships digests; {@code enforce} throws on a mismatch,
     * an unreadable path, or a missing digest.
     *
     * <p>Returns the canonicalized path to load. This is NOT full DLL-hijack
     * protection: once loaded, Windows resolves the DLL's own dependent imports
     * via the standard search order — constraining that is a launcher/native
     * concern.</p>
     */
    public static Path verifyIntegrity(Path dll) {
        boolean enforce = VERIFY_ENFORCE.equalsIgnoreCase(System.getProperty(VERIFY_PROP, ""));
        Path canonical;
        try {
            canonical = dll.toRealPath();
        } catch (IOException e) {
            return failOrWarn(enforce, dll, "cannot canonicalize native library path", e, dll);
        }
        Path sidecar = canonical.resolveSibling(canonical.getFileName() + SHA256_SIDECAR_SUFFIX);
        if (!Files.isRegularFile(sidecar)) {
            if (enforce) {
                throw new IllegalStateException("no integrity digest " + sidecar + " for " + canonical
                        + " (required by -D" + VERIFY_PROP + "=enforce)");
            }
            log.debug("native library {} has no integrity digest; set -D{}=enforce to require one",
                    canonical, VERIFY_PROP);
            return canonical;
        }
        try {
            String hex = Files.readString(sidecar).trim();
            int sp = hex.indexOf(' ');
            byte[] expected = HexFormat.of().parseHex(sp < 0 ? hex : hex.substring(0, sp));
            if (MessageDigest.isEqual(expected, sha256(canonical))) {
                log.debug("native library {} passed SHA-256 integrity check", canonical);
                return canonical;
            }
            return failOrWarn(enforce, canonical, "SHA-256 integrity check failed for", null, canonical);
        } catch (IOException e) {
            return failOrWarn(enforce, canonical, "integrity check error for", e, canonical);
        } catch (IllegalArgumentException e) {
            return failOrWarn(enforce, canonical, "malformed integrity digest for", null, canonical);
        }
    }

    private static byte[] sha256(Path file) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // mandatory in every JRE
        }
    }

    private static Path failOrWarn(boolean enforce, Path subject, String what, IOException cause, Path result) {
        if (enforce) {
            throw new IllegalStateException(what + " " + subject, cause);
        }
        if (cause != null) {
            log.warn("{} {}: {} (continuing — -D{}=enforce to block)",
                    what, subject, cause.getMessage(), VERIFY_PROP);
        } else {
            log.warn("{} {} (continuing — -D{}=enforce to block)", what, subject, VERIFY_PROP);
        }
        return result;
    }
}
