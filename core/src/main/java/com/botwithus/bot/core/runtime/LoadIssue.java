package com.botwithus.bot.core.runtime;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * One entry in the host's failed-load list ({@link LoadIssues}): a JAR that
 * did not load, or one that loaded but is shadowed by a newer JAR declaring
 * the same script name.
 */
public sealed interface LoadIssue permits LoadIssue.Failed, LoadIssue.DuplicateName {

    /** The folder the JAR was loaded from. */
    ScriptFolder folder();

    /** The JAR this entry is about, as it sits in the scripts folder. */
    Path jar();

    /** When {@link #jar()} was last modified, as read by the load that produced this entry. */
    Optional<Instant> lastModified();

    /**
     * {@code jar} did not load. Kept until a later pass loads that JAR cleanly
     * or the file is gone.
     *
     * @param error      why it did not load, stack trace included
     * @param recordedAt when the most recent failing pass ran
     */
    record Failed(ScriptFolder folder, Path jar, Throwable error, Optional<Instant> lastModified,
                  Instant recordedAt) implements LoadIssue {
    }

    /**
     * {@code jar} loaded, but {@code newerJar} declares the same script name.
     * The loaders hand scripts over newest JAR first, so {@code newerJar}'s
     * script is the one a runtime registers and {@code jar} is the older
     * duplicate to delete. A warning, not a failure: recomputed on every pass
     * and gone as soon as only one of the two remains.
     */
    record DuplicateName(ScriptFolder folder, Path jar, String scriptName, Path newerJar,
                         Optional<Instant> lastModified) implements LoadIssue {
    }
}
