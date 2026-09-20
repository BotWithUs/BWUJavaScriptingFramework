package com.botwithus.bot.core.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the ordered list of directories that might hold the NXT client's
 * {@code js5-*.jcache} files.
 *
 * <h2>What the client actually does</h2>
 * The cache directory is <b>not</b> a fixed path. {@code rs2client.exe} reads
 * {@code cache_folder} out of a {@code preferences.cfg} sitting beside it in
 * its {@code launcher/} directory, and the effective cache directory is
 * {@code <cache_folder>\<game name>}. The default is derived from a
 * known-folder call on first run and then written to that file — so once a user
 * moves it, nothing recomputes it and the default is wrong for them forever
 * after.
 *
 * <p>That is why each install contributes <b>two</b> candidates, in this order:
 * the directory its own {@code preferences.cfg} names, then the default for
 * that install. The preference wins where it is readable; the default covers a
 * client that has not written one yet.</p>
 *
 * <h2>The two install shapes</h2>
 * <ul>
 *   <li><b>Jagex launcher</b> — install root {@code %ProgramData%\Jagex},
 *       default cache {@code %ProgramData%\Jagex\RuneScape}.</li>
 *   <li><b>Steam</b> — install root
 *       {@code <library>\steamapps\common\RuneScape}, default cache
 *       {@code <library>\steamapps\common\RuneScape\RuneScape}. The doubled
 *       name is not a typo; it is the same {@code <cache_folder>\<game name>}
 *       rule. <b>A Steam library need not live under
 *       {@code %ProgramFiles(x86)%\Steam}</b>, so the other libraries are read
 *       from {@code steamapps\libraryfolders.vdf} rather than assumed.</li>
 * </ul>
 *
 * <p>Nothing here checks whether a candidate holds a cache — that is
 * {@link CacheSourceResolver#holdsGameCache} and it is applied to every entry.
 * A wrong guess therefore costs one directory probe, which is what makes it
 * safe to list speculative paths. It also disposes of a real decoy: a Steam
 * library can keep a {@code steamapps\common\RuneScape\launcher} directory left
 * behind by an uninstalled copy, with no cache under it.</p>
 *
 * <h2>Deliberately not probed</h2>
 * {@code %LOCALAPPDATA%\Jagex\RuneScape} is the {@code user_folder}, holding
 * only {@code Settings*.jcache}. It looks like a cache directory and is not
 * one; the {@code js5-*} glob is what keeps it out.
 */
public final class GameCacheCandidates {

    private static final Logger log = LoggerFactory.getLogger(GameCacheCandidates.class);

    /** The live client game name, and so the leaf of every cache directory. */
    private static final String GAME_NAME = "RuneScape";

    private static final String PREFERENCES_FILE = "preferences.cfg";
    private static final String CACHE_FOLDER_KEY = "cache_folder=";
    private static final String LAUNCHER_DIR = "launcher";

    private static final String PROGRAM_DATA_VARIABLE = "ProgramData";
    private static final String JAGEX_DIR = "Jagex";

    private static final String PROGRAM_FILES_X86_VARIABLE = "ProgramFiles(x86)";
    private static final String STEAM_DIR = "Steam";
    private static final String STEAM_APPS_DIR = "steamapps";
    private static final String STEAM_COMMON_DIR = "common";
    private static final String LIBRARY_FOLDERS_FILE = "libraryfolders.vdf";

    /** Matches a {@code "path"} entry in a Valve KeyValues file. */
    private static final Pattern LIBRARY_PATH = Pattern.compile("\"path\"\\s+\"(.+?)\"");

    /** A VDF string escapes a backslash by doubling it. */
    private static final String VDF_ESCAPED_SEPARATOR = "\\\\";
    private static final String SEPARATOR = "\\";

    private GameCacheCandidates() {
    }

    /**
     * Cache directories to probe, in preference order.
     *
     * @param environment reads an environment variable by name, {@code null} when unset
     */
    public static List<Path> forEnvironment(UnaryOperator<String> environment) {
        Objects.requireNonNull(environment, "environment");
        List<Path> candidates = new ArrayList<>();
        jagexLauncherRoot(environment).ifPresent(root -> addInstall(candidates, root));
        for (Path library : steamLibraries(environment)) {
            addInstall(candidates, library.resolve(STEAM_APPS_DIR)
                    .resolve(STEAM_COMMON_DIR).resolve(GAME_NAME));
        }
        return List.copyOf(new LinkedHashSet<>(candidates));
    }

    /**
     * Reads {@code cache_folder} out of a client {@code preferences.cfg}.
     * Empty when the file is absent, unreadable, or carries no such key — each
     * of which is an ordinary state rather than an error.
     */
    public static Optional<Path> cacheFolder(Path preferencesFile) {
        Objects.requireNonNull(preferencesFile, "preferencesFile");
        if (!Files.isRegularFile(preferencesFile)) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(preferencesFile)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(CACHE_FOLDER_KEY)) {
                    return toPath(trimmed.substring(CACHE_FOLDER_KEY.length()).trim());
                }
            }
        } catch (IOException e) {
            log.debug("cannot read {}: {}", preferencesFile, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Every Steam library root: the default install, plus each {@code "path"}
     * entry in its {@code libraryfolders.vdf}. Order is preserved and
     * duplicates dropped, since the default library is itself listed there.
     */
    public static List<Path> steamLibraries(UnaryOperator<String> environment) {
        Objects.requireNonNull(environment, "environment");
        Set<Path> libraries = new LinkedHashSet<>();
        Optional<Path> defaultRoot = resolveUnder(environment, PROGRAM_FILES_X86_VARIABLE, STEAM_DIR);
        defaultRoot.ifPresent(libraries::add);
        defaultRoot.map(root -> root.resolve(STEAM_APPS_DIR).resolve(LIBRARY_FOLDERS_FILE))
                .ifPresent(vdf -> libraries.addAll(parseLibraryFolders(vdf)));
        return List.copyOf(libraries);
    }

    /** Adds the preference-named cache directory then the default one, for one install root. */
    private static void addInstall(List<Path> candidates, Path installRoot) {
        cacheFolder(installRoot.resolve(LAUNCHER_DIR).resolve(PREFERENCES_FILE))
                .map(folder -> folder.resolve(GAME_NAME))
                .ifPresent(candidates::add);
        candidates.add(installRoot.resolve(GAME_NAME));
    }

    private static Optional<Path> jagexLauncherRoot(UnaryOperator<String> environment) {
        return resolveUnder(environment, PROGRAM_DATA_VARIABLE, JAGEX_DIR);
    }

    private static List<Path> parseLibraryFolders(Path vdf) {
        if (!Files.isRegularFile(vdf)) {
            return List.of();
        }
        List<Path> libraries = new ArrayList<>();
        try {
            Matcher matcher = LIBRARY_PATH.matcher(Files.readString(vdf));
            while (matcher.find()) {
                toPath(matcher.group(1).replace(VDF_ESCAPED_SEPARATOR, SEPARATOR))
                        .ifPresent(libraries::add);
            }
        } catch (IOException e) {
            log.debug("cannot read {}: {}", vdf, e.getMessage());
        }
        return List.copyOf(libraries);
    }

    private static Optional<Path> resolveUnder(UnaryOperator<String> environment,
                                               String variable, String child) {
        String root = environment.apply(variable);
        if (root == null || root.isBlank()) {
            return Optional.empty();
        }
        return toPath(root).map(path -> path.resolve(child));
    }

    /** A value read out of a config file this host does not own may not be a path at all. */
    private static Optional<Path> toPath(String value) {
        if (value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(value));
        } catch (InvalidPathException e) {
            log.debug("not a usable path: {}", value);
            return Optional.empty();
        }
    }
}
