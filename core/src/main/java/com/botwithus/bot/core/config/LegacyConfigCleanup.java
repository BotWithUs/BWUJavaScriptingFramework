package com.botwithus.bot.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * One-shot hard-cut migration for the
 * {@code account_uuid}-keying re-layout (see plan
 * {@code humming-plotting-cerf}).
 *
 * <p>Pre-migration layout keyed both stores by in-game display name:
 * <ul>
 *   <li>{@code ~/.botwithus/profiles/<displayName>.properties}
 *   <li>{@code ~/.botwithus/config/<scriptName>.json}
 * </ul>
 *
 * <p>Post-migration both are keyed by the agent's stable
 * {@code account_uuid}; configs additionally live in a per-account
 * subdirectory. The migration drops the legacy files; the user starts fresh
 * on first run. Group profiles ({@code profiles/groups/*}) are untouched —
 * groups are not per-account.
 *
 * <p>A sentinel file at {@code ~/.botwithus/.account-uuid-keying-v1} gates
 * the run; once present, the cleanup is a no-op.
 */
final class LegacyConfigCleanup {

    private static final Logger log = LoggerFactory.getLogger(LegacyConfigCleanup.class);
    private static final String SENTINEL_NAME = ".account-uuid-keying-v1";

    private LegacyConfigCleanup() {}

    /**
     * Runs the cleanup against {@code baseDir} if the sentinel is missing.
     * Safe to call repeatedly; subsequent calls short-circuit on the sentinel.
     * Failures are logged but never thrown — a half-cleaned tree is preferable
     * to crashing the app at startup.
     */
    static void runIfNeeded(Path baseDir) {
        Path sentinel = baseDir.resolve(SENTINEL_NAME);
        if (Files.exists(sentinel)) {
            return;
        }
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            log.warn("Cannot create {} for migration sentinel: {}", baseDir, e.getMessage());
            return;
        }

        int deletedProfiles = deleteTopLevelFiles(baseDir.resolve("profiles"), "properties");
        int deletedConfigJson = deleteTopLevelFiles(baseDir.resolve("config"), "json");
        int deletedConfigProps = deleteTopLevelFiles(baseDir.resolve("config"), "properties");

        try {
            Files.createFile(sentinel);
        } catch (IOException e) {
            log.warn("Failed to write migration sentinel {}: {}", sentinel, e.getMessage());
            return;
        }

        if (deletedProfiles + deletedConfigJson + deletedConfigProps > 0) {
            log.info("Cleared legacy display-name-keyed profiles/configs ({} profiles, {} json configs, {} properties configs). See plan humming-plotting-cerf.",
                    deletedProfiles, deletedConfigJson, deletedConfigProps);
        }
    }

    /**
     * Removes regular files with the given extension directly inside {@code dir}.
     * Subdirectories (including {@code profiles/groups/}) are left untouched.
     */
    private static int deleteTopLevelFiles(Path dir, String extension) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        String suffix = "." + extension;
        int count = 0;
        try (Stream<Path> entries = Files.list(dir)) {
            var paths = entries.toList();
            for (Path p : paths) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                if (!p.getFileName().toString().endsWith(suffix)) {
                    continue;
                }
                try {
                    Files.delete(p);
                    count++;
                } catch (IOException e) {
                    log.warn("Failed to delete legacy {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan {} for legacy cleanup: {}", dir, e.getMessage());
        }
        return count;
    }
}
