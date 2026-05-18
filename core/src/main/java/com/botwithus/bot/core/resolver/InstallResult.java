package com.botwithus.bot.core.resolver;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Outcome of a single install / update / uninstall operation. Distinct
 * subtypes for "no-op success" vs "did work" let the CLI render an
 * informative message without inspecting paths.
 */
public sealed interface InstallResult
        permits InstallResult.Installed,
                InstallResult.AlreadyInstalled,
                InstallResult.Updated,
                InstallResult.NoUpdateAvailable,
                InstallResult.Uninstalled,
                InstallResult.NotInstalled,
                InstallResult.ResolveFailed,
                InstallResult.IoError {

    /** Newly installed; the JAR was not previously present in the index. */
    record Installed(MavenCoord coord, Path jar) implements InstallResult {}

    /** The exact same {@code groupId:artifactId:version} was already installed. */
    record AlreadyInstalled(MavenCoord coord, Path existingJar) implements InstallResult {}

    /** Successfully updated from {@code oldJar} to {@code newJar}. */
    record Updated(MavenCoord coord, Path oldJar, Path newJar) implements InstallResult {}

    /** An update was requested but no newer version exists in any repository. */
    record NoUpdateAvailable(MavenCoord coord, String installedVersion) implements InstallResult {}

    /** Previously-installed artifact was uninstalled; the JAR was deleted. */
    record Uninstalled(MavenCoord coord, Path removedJar) implements InstallResult {}

    /** Uninstall requested for an artifact that was not in the index. */
    record NotInstalled(MavenCoord coord) implements InstallResult {}

    /**
     * The resolver returned a non-success outcome; carries the original
     * {@link ResolveOutcome} for diagnostic display.
     */
    record ResolveFailed(MavenCoord coord, ResolveOutcome outcome) implements InstallResult {}

    /**
     * A filesystem error occurred while moving the JAR into {@code scripts/}
     * or updating the install index.
     */
    record IoError(MavenCoord coord, IOException cause) implements InstallResult {}
}
