package com.botwithus.bot.core.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the cache directory of a <em>running</em> NXT client from its pid.
 *
 * <p>This is the authoritative answer, and it is the reason this class exists
 * alongside {@link GameCacheCandidates}. That class probes the install shapes we
 * have seen; this one asks the client that is actually running. Since
 * {@code cache_folder} is a user-relocatable preference that nothing recomputes
 * once moved, a list of known locations is wrong <em>forever</em> for a user who
 * relocated their cache, however long the list grows.</p>
 *
 * <h2>How the client decides</h2>
 * {@code rs2client.exe} reads {@code cache_folder} out of a
 * {@code preferences.cfg} and uses {@code <cache_folder>\<game name>} as its
 * cache directory. The pid gives us the executable path in pure Java —
 * {@link ProcessHandle#info()} — and the rest is filesystem work.
 *
 * <h2>The preferences file is not always beside the executable</h2>
 * It is for the two ordinary installs, but not for beta, which is why this
 * walks up rather than looking sideways:
 * <ul>
 *   <li>{@code %ProgramData%\Jagex\launcher\rs2client.exe} — sibling
 *       {@code preferences.cfg}.</li>
 *   <li>{@code <library>\steamapps\common\RuneScape\launcher\rs2client.exe} —
 *       sibling {@code preferences.cfg}.</li>
 *   <li>{@code %ProgramData%\Jagex\launcher\BETA\rs2client.exe} — <b>no</b>
 *       {@code preferences.cfg} in {@code BETA\} at all; that directory holds
 *       the executable and nothing else. The one in the parent
 *       {@code launcher\} is what applies.</li>
 * </ul>
 *
 * <h2>Why the game name is probed rather than derived</h2>
 * The leaf is {@code RuneScape} for both ordinary installs and
 * {@code RuneScape-BETA} for beta, and <b>nothing reachable from the pid states
 * which</b>. The client binaries carry a game-name table ({@code RuneScape},
 * {@code StellarDawn}, {@code Old School RuneScape}, {@code Scratch}, …) that
 * contains no beta entry, so the variant is supplied at runtime by whatever
 * launched the client — and {@link ProcessHandle.Info#arguments()} is
 * empty on Windows, so that parameter cannot be read back.
 *
 * <p>So the name is not derived. Candidate leaves are tried in order and the
 * first that actually <em>holds</em> a cache wins:</p>
 * <ol>
 *   <li>{@code RuneScape-<DIR>} for each directory between the preferences
 *       directory and the executable, deepest first. This is a heuristic from a
 *       single observation — the beta executable sits in a directory named
 *       {@code BETA} and its cache leaf is {@code RuneScape-BETA}.</li>
 *   <li>{@code RuneScape}, the default both non-beta installs use.</li>
 *   <li>The one immediate subdirectory of {@code cache_folder} that holds a
 *       cache, <b>and only when exactly one does</b>. A box with both a live and
 *       a beta cache is ambiguous, and guessing there would hand a live client
 *       the beta cache silently — so it declines instead and lets the lower
 *       tiers answer.</li>
 * </ol>
 *
 * <p>Every candidate is gated on {@link CacheSourceResolver#holdsGameCache},
 * so a wrong guess costs one directory probe. That gate is also what walks past
 * a decoy: a {@code steamapps\common\RuneScape\launcher} left behind by an
 * uninstalled copy resolves to a {@code cache_folder} with no index files under
 * it, and is refused rather than selected.</p>
 *
 * <p>Nothing here throws. Every step — a pid that has since exited, an
 * executable path we cannot read, an absent or key-less {@code preferences.cfg},
 * a {@code cache_folder} naming a directory that no longer exists — is an
 * ordinary state that yields an empty result, so the caller falls through to the
 * next tier rather than failing the host's startup.</p>
 */
public final class RunningClientCache {

    /**
     * A resolved cache directory together with the reason its leaf was chosen.
     *
     * <p>The reason is carried rather than recomputed so one {@code info} line
     * can name the tier, the heuristic and the directory together. A wrong
     * resolution shows up as a script reading odd values, never as an exception,
     * so it has to be diagnosable from a user's log alone.</p>
     */
    private record Resolution(Path directory, String how) {
    }

    private static final Logger log = LoggerFactory.getLogger(RunningClientCache.class);

    /**
     * How far above the executable to look for a {@code preferences.cfg}.
     * Beta needs one level; the bound keeps a client installed somewhere
     * unexpected from walking to the filesystem root and adopting an unrelated
     * file on the way.
     */
    private static final int MAX_PREFERENCES_SEARCH_DEPTH = 3;

    /** Joins the base game name to a variant, as in {@code RuneScape-BETA}. */
    private static final String VARIANT_SEPARATOR = "-";

    private RunningClientCache() {
    }

    /**
     * The cache directory of the client running as {@code pid}, when one can be
     * established and actually holds index files.
     *
     * <p>Empty when the process has exited, when its executable path is not
     * readable, or when no candidate leaf under its {@code cache_folder} holds a
     * cache. The pid being <em>present</em> is not the same as this succeeding,
     * which is why callers keep a lower tier behind it.</p>
     */
    public static Optional<Path> forPid(long pid) {
        Optional<Path> executable = ProcessHandle.of(pid)
                .flatMap(handle -> handle.info().command())
                .flatMap(GameCacheCandidates::toPath);
        if (executable.isEmpty()) {
            log.debug("no readable executable path for pid {}; it may have exited", pid);
            return Optional.empty();
        }
        Optional<Resolution> resolution = resolveFor(executable.get());
        resolution.ifPresent(resolved -> log.info(
                "NXTCache: the client running as pid {} ({}) names its cache at {} — leaf chosen "
                        + "because it is {}",
                pid, executable.get(), resolved.directory(), resolved.how()));
        return resolution.map(Resolution::directory);
    }

    /**
     * The cache directory belonging to a client executable at {@code executable},
     * by reading the {@code preferences.cfg} that governs it.
     *
     * <p>Separate from {@link #forPid} so the whole path from an executable to a
     * cache directory is testable against a real install without a running game.
     * The executable's <em>name</em> is deliberately not checked: the pid a
     * caller passes came from the agent's own pipe or mapping, so the process is
     * the game client by construction, and requiring a spelling would only add a
     * way to fail when a variant is named differently.</p>
     */
    public static Optional<Path> forExecutable(Path executable) {
        return resolveFor(executable).map(Resolution::directory);
    }

    private static Optional<Resolution> resolveFor(Path executable) {
        Objects.requireNonNull(executable, "executable");
        Path executableDirectory = executable.getParent();
        if (executableDirectory == null) {
            return Optional.empty();
        }
        return preferencesDirectoryAbove(executableDirectory)
                .flatMap(preferences -> cacheDirectoryUnder(preferences, executableDirectory));
    }

    /** The nearest directory at or above {@code start} holding a {@code preferences.cfg}. */
    private static Optional<Path> preferencesDirectoryAbove(Path start) {
        Path directory = start;
        for (int depth = 0; depth <= MAX_PREFERENCES_SEARCH_DEPTH && directory != null; depth++) {
            if (Files.isRegularFile(GameCacheCandidates.preferencesIn(directory))) {
                return Optional.of(directory);
            }
            directory = directory.getParent();
        }
        log.debug("no {} at or above {}", GameCacheCandidates.PREFERENCES_FILE, start);
        return Optional.empty();
    }

    /** Applies the game-name preference order documented on this class. */
    private static Optional<Resolution> cacheDirectoryUnder(Path preferencesDirectory,
                                                            Path executableDirectory) {
        Optional<Path> cacheFolder = GameCacheCandidates.cacheFolder(
                GameCacheCandidates.preferencesIn(preferencesDirectory));
        if (cacheFolder.isEmpty()) {
            return Optional.empty();
        }
        Path folder = cacheFolder.get();
        String defaultName = GameCacheCandidates.DEFAULT_GAME_NAME;
        for (String name : gameNames(preferencesDirectory, executableDirectory)) {
            Path directory = folder.resolve(name);
            if (CacheSourceResolver.holdsGameCache(directory)) {
                String how = name.equals(defaultName)
                        ? "the default game name, holding a cache"
                        : "the variant named by the executable's own directory, holding a cache";
                return Optional.of(new Resolution(directory, how));
            }
        }
        return soleCacheUnder(folder);
    }

    /**
     * Game-name leaves to try: each variant suggested by the executable's
     * position below the preferences directory, deepest first, then the default.
     */
    private static List<String> gameNames(Path preferencesDirectory, Path executableDirectory) {
        List<String> names = new ArrayList<>();
        if (executableDirectory.startsWith(preferencesDirectory)) {
            for (Path element : preferencesDirectory.relativize(executableDirectory)) {
                String variant = element.toString();
                if (!variant.isBlank()) {
                    names.addFirst(GameCacheCandidates.DEFAULT_GAME_NAME + VARIANT_SEPARATOR + variant);
                }
            }
        }
        names.add(GameCacheCandidates.DEFAULT_GAME_NAME);
        return List.copyOf(names);
    }

    /**
     * The single immediate subdirectory of {@code cacheFolder} holding a cache.
     * Empty when none does, and <b>also</b> empty when several do — see the
     * class javadoc for why ambiguity declines rather than guesses.
     */
    private static Optional<Resolution> soleCacheUnder(Path cacheFolder) {
        List<Path> holders = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(cacheFolder)) {
            for (Path entry : entries) {
                if (CacheSourceResolver.holdsGameCache(entry)) {
                    holders.add(entry);
                }
            }
        } catch (IOException e) {
            log.debug("cannot list {}: {}", cacheFolder, e.getMessage());
            return Optional.empty();
        }
        if (holders.size() == 1) {
            return Optional.of(new Resolution(holders.getFirst(),
                    "the only directory under " + cacheFolder + " holding a cache"));
        }
        if (!holders.isEmpty()) {
            log.debug("{} directories under {} hold a cache and none was named; declining to guess",
                    holders.size(), cacheFolder);
        }
        return Optional.empty();
    }
}
