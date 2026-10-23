package com.botwithus.bot.cli.sdnlive;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one run is given, all of it by the {@code :cli:sdnLiveCheck} task.
 *
 * @param courier       the courier executable
 * @param seed          the seed the runtime was told the public half of
 * @param wrongSeed     a different seed, for the negative control
 * @param pid           the game client to connect to
 * @param workDir       this run's scratch directory; everything the run writes is under it
 * @param hostLog       where this process's log is written, read back at the end
 * @param jarsByVariant the fixture jars, keyed by variant
 */
record SdnLiveConfig(Path courier, String seed, String wrongSeed, long pid, Path workDir, Path hostLog,
                     Map<String, Path> jarsByVariant) {

    static final String PREFIX = "botwithus.sdnlive.";
    private static final Pattern FIXTURE_JAR = Pattern.compile("sdn-live-([a-z]+)\\.jar");

    /** Reads the run's settings; throws naming the first one that is missing. */
    static SdnLiveConfig fromSystemProperties() {
        return new SdnLiveConfig(
                existing(Path.of(required("courier"))),
                required("seed"),
                required("wrongSeed"),
                Long.parseLong(required("pid")),
                Path.of(required("workDir")),
                Path.of(required("hostLog")),
                fixtures(required("fixtures")));
    }

    private static Map<String, Path> fixtures(String pathList) {
        Map<String, Path> byVariant = new LinkedHashMap<>();
        for (String entry : pathList.split(File.pathSeparator)) {
            Path jar = existing(Path.of(entry));
            Matcher m = FIXTURE_JAR.matcher(jar.getFileName().toString());
            if (m.matches()) {
                byVariant.put(m.group(1), jar);
            }
        }
        return byVariant;
    }

    private static String required(String key) {
        String value = System.getProperty(PREFIX + key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("-D" + PREFIX + key + " is not set; run this through :cli:sdnLiveCheck");
        }
        return value;
    }

    private static Path existing(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException(path + " does not exist");
        }
        return path;
    }
}
