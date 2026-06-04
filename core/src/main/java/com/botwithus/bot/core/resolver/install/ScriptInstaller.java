package com.botwithus.bot.core.resolver.install;

import com.botwithus.bot.core.resolver.InstallResult;
import com.botwithus.bot.core.resolver.MavenCoord;
import com.botwithus.bot.core.resolver.ResolveOutcome;
import com.botwithus.bot.core.resolver.metadata.ChecksumDigest;
import com.botwithus.bot.core.resolver.metadata.PomProperties;
import com.botwithus.bot.core.resolver.pipeline.Resolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Installs and uninstalls script JARs. Owns the {@code scripts/} directory
 * and the {@link InstalledIndex} sidecar; constructor-injected, no
 * statics.
 *
 * <p>{@code onScriptsChanged} is a function-typed parameter — null
 * permitted. The CLI wires it to reload the {@code ScriptRuntime}; 12.1
 * tests pass a no-op or a counter.</p>
 */
public final class ScriptInstaller {

    private static final Logger log = LoggerFactory.getLogger(ScriptInstaller.class);

    private final Resolver resolver;
    private final Path scriptsDir;
    private final InstalledIndex index;
    private final Runnable onScriptsChanged;
    private final Clock clock;

    public ScriptInstaller(Resolver resolver,
                           Path scriptsDir,
                           InstalledIndex index,
                           Runnable onScriptsChanged) {
        this(resolver, scriptsDir, index, onScriptsChanged, Clock.systemUTC());
    }

    public ScriptInstaller(Resolver resolver,
                           Path scriptsDir,
                           InstalledIndex index,
                           Runnable onScriptsChanged,
                           Clock clock) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.scriptsDir = Objects.requireNonNull(scriptsDir, "scriptsDir");
        this.index = Objects.requireNonNull(index, "index");
        this.onScriptsChanged = onScriptsChanged != null ? onScriptsChanged : () -> {};
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public InstallResult install(MavenCoord coord) {
        Objects.requireNonNull(coord, "coord");

        Optional<InstalledEntry> existing = index.find(coord);
        if (existing.isPresent() && coord.version().isPresent()
                && existing.get().version().equals(coord.version().get())) {
            return alreadyInstalled(coord, existing.get());
        }

        ResolveOutcome outcome = resolver.resolve(coord);
        Optional<ResolveOutcome.Resolved> resolvedOpt = asResolved(outcome);
        if (resolvedOpt.isEmpty()) {
            return new InstallResult.ResolveFailed(coord, outcome);
        }
        ResolveOutcome.Resolved resolved = resolvedOpt.get();

        if (existing.isPresent() && existing.get().version().equals(resolved.artifact().resolvedVersion())) {
            return alreadyInstalled(coord, existing.get());
        }

        try {
            return performInstall(coord, resolved);
        } catch (IOException e) {
            log.warn("install IO failure for {}", coord, e);
            return new InstallResult.IoError(coord, e);
        }
    }

    private InstallResult alreadyInstalled(MavenCoord coord, InstalledEntry entry) {
        return new InstallResult.AlreadyInstalled(
                MavenCoord.of(coord.groupId(), coord.artifactId(), entry.version()),
                scriptsDir.resolve(entry.jarFilename()));
    }

    private InstallResult performInstall(MavenCoord coord, ResolveOutcome.Resolved resolved) throws IOException {
        Files.createDirectories(scriptsDir);
        String jarName = resolved.artifact().coord().jarFileName(resolved.artifact().resolvedVersion());
        Path destination = scriptsDir.resolve(jarName);
        moveWithAtomicFallback(resolved.artifact().jar(), destination);

        InstalledEntry entry = new InstalledEntry(
                jarName,
                resolved.artifact().coord(),
                Instant.now(clock),
                new ChecksumDigest(resolved.artifact().sha256()).toHex(),
                resolved.artifact().repository().id());

        Optional<Path> oldJar = removeStaleJar(coord, jarName);
        index.put(entry);
        index.save();
        onScriptsChanged.run();

        if (oldJar.isPresent()) {
            return new InstallResult.Updated(resolved.artifact().coord(), oldJar.get(), destination);
        }
        return new InstallResult.Installed(resolved.artifact().coord(), destination);
    }

    public InstallResult update(MavenCoord coord) {
        Objects.requireNonNull(coord, "coord");
        Optional<InstalledEntry> existing = index.find(coord);
        if (existing.isEmpty()) {
            return new InstallResult.NotInstalled(coord);
        }
        ResolveOutcome outcome = resolver.resolve(MavenCoord.of(coord.groupId(), coord.artifactId()));
        Optional<ResolveOutcome.Resolved> resolvedOpt = asResolved(outcome);
        if (resolvedOpt.isEmpty()) {
            return new InstallResult.ResolveFailed(coord, outcome);
        }
        String installed = existing.get().version();
        String latest = resolvedOpt.get().artifact().resolvedVersion();
        if (installed.equals(latest)) {
            return new InstallResult.NoUpdateAvailable(coord, installed);
        }
        return install(MavenCoord.of(coord.groupId(), coord.artifactId(), latest));
    }

    /**
     * Narrows a {@link ResolveOutcome} to {@link ResolveOutcome.Resolved}
     * via an exhaustive sealed switch. Returns empty for every failure
     * variant; the compiler enforces that new variants added to
     * {@code ResolveOutcome} are handled here.
     */
    private static Optional<ResolveOutcome.Resolved> asResolved(ResolveOutcome outcome) {
        return switch (outcome) {
            case ResolveOutcome.Resolved r -> Optional.of(r);
            case ResolveOutcome.NotFound nf -> Optional.empty();
            case ResolveOutcome.ChecksumMismatch cm -> Optional.empty();
            case ResolveOutcome.SignatureInvalid si -> Optional.empty();
            case ResolveOutcome.TransportFailure tf -> Optional.empty();
        };
    }

    public InstallResult uninstall(MavenCoord coord) {
        Objects.requireNonNull(coord, "coord");
        Optional<InstalledEntry> existing = index.find(coord);
        if (existing.isEmpty()) {
            return new InstallResult.NotInstalled(coord);
        }
        Path jar = scriptsDir.resolve(existing.get().jarFilename());
        try {
            Files.deleteIfExists(jar);
            index.remove(coord);
            index.save();
            onScriptsChanged.run();
            return new InstallResult.Uninstalled(
                    MavenCoord.of(coord.groupId(), coord.artifactId(), existing.get().version()),
                    jar);
        } catch (IOException e) {
            log.warn("uninstall IO failure for {}", coord, e);
            return new InstallResult.IoError(coord, e);
        }
    }

    public List<InstalledEntry> listInstalled() {
        return index.all();
    }

    /**
     * Bridges a manually-dropped JAR into the install index by reading
     * its {@code META-INF/maven/.../pom.properties}. Used by
     * {@code scripts adopt}.
     *
     * @param jarFileName filename within {@link #scriptsDir}
     */
    public InstallResult adopt(String jarFileName) {
        Objects.requireNonNull(jarFileName, "jarFileName");
        Path jar = scriptsDir.resolve(jarFileName);
        if (!Files.isRegularFile(jar)) {
            return new InstallResult.IoError(
                    MavenCoord.of("unknown", jarFileName),
                    new IOException("not a regular file: " + jar));
        }
        Optional<PomProperties> props;
        try {
            props = PomProperties.read(jar);
        } catch (IOException e) {
            return new InstallResult.IoError(MavenCoord.of("unknown", jarFileName), e);
        }
        if (props.isEmpty()) {
            return new InstallResult.IoError(
                    MavenCoord.of("unknown", jarFileName),
                    new IOException("no META-INF/maven/.../pom.properties entry in " + jarFileName));
        }
        MavenCoord coord = props.get().coord();
        if (index.find(coord).isPresent()) {
            return new InstallResult.AlreadyInstalled(coord, jar);
        }
        try {
            String digest = ChecksumDigest.of(jar).toHex();
            index.put(new InstalledEntry(jarFileName, coord, Instant.now(clock), digest, ADOPTED_REPO_ID));
            index.save();
            onScriptsChanged.run();
            return new InstallResult.Installed(coord, jar);
        } catch (IOException e) {
            return new InstallResult.IoError(coord, e);
        }
    }

    /**
     * Returns installed entries that have a newer version available in
     * any configured repository. Backs {@code scripts list --installed
     * --outdated} and {@code scripts update --all}.
     */
    public List<OutdatedEntry> listOutdated() {
        List<OutdatedEntry> out = new ArrayList<>();
        for (InstalledEntry entry : index.all()) {
            MavenCoord lookup = MavenCoord.of(entry.coord().groupId(), entry.coord().artifactId());
            ResolveOutcome outcome = resolver.resolve(lookup);
            Optional<ResolveOutcome.Resolved> resolvedOpt = asResolved(outcome);
            if (resolvedOpt.isEmpty()) {
                continue;
            }
            ResolveOutcome.Resolved resolved = resolvedOpt.get();
            String latest = resolved.artifact().resolvedVersion();
            if (!latest.equals(entry.version())) {
                out.add(new OutdatedEntry(entry, latest));
            }
            try {
                Files.deleteIfExists(resolved.artifact().jar());
            } catch (IOException e) {
                log.debug("failed to delete staged jar {} after listOutdated", resolved.artifact().jar(), e);
            }
        }
        return List.copyOf(out);
    }

    /** Repository id used for entries adopted from JARs not installed by the resolver. */
    public static final String ADOPTED_REPO_ID = "adopted";

    /** One row of {@link #listOutdated()}. */
    public record OutdatedEntry(InstalledEntry installed, String latestVersion) {}

    /**
     * Deletes the previous JAR for the same {@code groupId:artifactId} (if
     * any) prior to writing the new one. The new JAR's filename can match
     * the old one when versions match exactly — handled by the caller's
     * {@code AlreadyInstalled} short-circuit.
     */
    private Optional<Path> removeStaleJar(MavenCoord coord, String newJarName) throws IOException {
        Optional<InstalledEntry> prior = index.find(coord);
        if (prior.isEmpty()) {
            return Optional.empty();
        }
        String oldName = prior.get().jarFilename();
        if (oldName.equals(newJarName)) {
            return Optional.empty();
        }
        Path oldJar = scriptsDir.resolve(oldName);
        if (Files.deleteIfExists(oldJar)) {
            return Optional.of(oldJar);
        }
        return Optional.empty();
    }

    /**
     * Moves the staged JAR into {@code scripts/}. Tries an atomic move first
     * (so concurrent script loaders never see a half-written file); falls
     * back to a non-atomic replace when the source and destination live on
     * different filesystems (cross-FS atomic move is not supported).
     */
    private static void moveWithAtomicFallback(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException atomicFailed) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
