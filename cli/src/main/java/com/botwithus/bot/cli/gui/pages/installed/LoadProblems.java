package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.core.runtime.JarLoadOutcome;
import com.botwithus.bot.core.runtime.LoadIssue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns the host's failed-load list for the scripts folder into the page's
 * "Failed to load" entries.
 */
public final class LoadProblems {

    private static final String JAR_SUFFIX = ".jar";
    /** A dash followed by a digit starts the version part of a JAR name: {@code woodcutting-1.0-SNAPSHOT}. */
    private static final Pattern VERSION_START = Pattern.compile("-[0-9]");

    private LoadProblems() {}

    /**
     * @param issues      the scripts folder's entries from the failed-load list
     * @param latestPass  every JAR of the latest load pass, for spotting a failed JAR
     *                    that is an older build of one that loaded
     * @param folderLabel the folder as the page names it, such as {@code "scripts/"}
     */
    public static List<LoadProblem> of(List<LoadIssue> issues, List<? extends JarLoadOutcome> latestPass,
                                       String folderLabel) {
        return issues.stream().map(issue -> switch (issue) {
            case LoadIssue.Failed f -> failed(f, latestPass, folderLabel);
            case LoadIssue.DuplicateName d -> duplicate(d, folderLabel);
        }).toList();
    }

    private static LoadProblem failed(LoadIssue.Failed f, List<? extends JarLoadOutcome> latestPass,
                                      String folderLabel) {
        return new LoadProblem(LoadProblem.Kind.FAILED, f.jar(), oneLine(f.error()), stackTraceOf(f.error()),
                newerBuildOf(f, latestPass).map(newer -> "An older build of " + newer.scriptName().orElseThrow()
                        + " sits next to " + newer.jar().getFileName() + ". Delete it from " + folderLabel
                        + " or fix its module-info provides line."));
    }

    private static LoadProblem duplicate(LoadIssue.DuplicateName d, String folderLabel) {
        return new LoadProblem(LoadProblem.Kind.OLDER_DUPLICATE, d.jar(),
                "Older copy of " + d.scriptName() + ": " + d.newerJar().getFileName() + " is the one that runs", "",
                Optional.of("Delete it from " + folderLabel + " to clear this."));
    }

    /**
     * A JAR that loaded, whose name differs from {@code f}'s only in its version
     * and which is not older than it: {@code f} is then most likely a build left
     * behind when a newer one was dropped in.
     */
    private static Optional<? extends JarLoadOutcome> newerBuildOf(LoadIssue.Failed f,
                                                                  List<? extends JarLoadOutcome> latestPass) {
        String stem = stemOf(f.jar());
        Instant failedAt = f.lastModified().orElse(Instant.MIN);
        return latestPass.stream()
                .filter(o -> o.isSuccess() && o.scriptName().isPresent())
                .filter(o -> !o.jar().equals(f.jar()) && stemOf(o.jar()).equals(stem))
                .filter(o -> !o.lastModified().orElse(Instant.MAX).isBefore(failedAt))
                .findFirst();
    }

    /** {@code woodcutting-1.0-SNAPSHOT.jar} and {@code Woodcutting-2.0.jar} both as {@code woodcutting}. */
    static String stemOf(Path jar) {
        String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(JAR_SUFFIX)) {
            name = name.substring(0, name.length() - JAR_SUFFIX.length());
        }
        var version = VERSION_START.matcher(name);
        return version.find() ? name.substring(0, version.start()) : name;
    }

    /** {@code "ServiceConfigurationError: BotScript provider not found"}: the type and the message's first line. */
    private static String oneLine(Throwable error) {
        String type = error.getClass().getSimpleName();
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return type;
        }
        int nl = message.indexOf('\n');
        return type + ": " + (nl < 0 ? message : message.substring(0, nl)).strip();
    }

    private static String stackTraceOf(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString().stripTrailing();
    }
}
