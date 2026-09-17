package com.botwithus.bot.skilling.atlas;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves the on-disk location of the Atlas ({@code resolved.sqlite}).
 *
 * <p>The Atlas is downloaded by {@code BotWithUs-Launcher} into
 * {@code ~/.botwithus/native/}, the same directory that holds {@code NXTCache.dll}
 * and {@code worldwalker.dll}. This mirrors core's {@code NativeCache} precedence
 * but is self-contained so {@code skilling-core} need not depend on {@code core}:
 * the {@code -Dbotwithus.atlas} override wins (when it points at an existing file),
 * otherwise the cache entry under {@code ~/.botwithus/native/}.</p>
 */
public final class AtlasPaths {

    /** File name of the baked Atlas within the native cache. */
    public static final String ATLAS_FILE_NAME = "resolved.sqlite";

    /** Dev override system property: an absolute path to a {@code resolved.sqlite}. */
    public static final String OVERRIDE_PROPERTY = "botwithus.atlas";

    private AtlasPaths() {}

    /** Default native-cache location of the Atlas; existence is not checked. */
    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"))
                .resolve(".botwithus").resolve("native").resolve(ATLAS_FILE_NAME);
    }

    /**
     * Locate the Atlas without opening it: the {@code -Dbotwithus.atlas} override
     * first (when it points at an existing file), then the native-cache entry.
     * Empty when neither is present.
     */
    public static Optional<Path> locate() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (Files.isRegularFile(p)) {
                return Optional.of(p);
            }
        }
        Path cached = defaultPath();
        return Files.isRegularFile(cached) ? Optional.of(cached) : Optional.empty();
    }
}
