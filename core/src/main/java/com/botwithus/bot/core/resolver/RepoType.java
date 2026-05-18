package com.botwithus.bot.core.resolver;

/**
 * Maven repository layout type. Releases reject {@code -SNAPSHOT} versions;
 * snapshots resolve {@code maven-metadata.xml} timestamped builds.
 */
public enum RepoType {
    RELEASE,
    SNAPSHOT
}
