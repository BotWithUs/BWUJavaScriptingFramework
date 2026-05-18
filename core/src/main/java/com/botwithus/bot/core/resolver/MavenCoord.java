package com.botwithus.bot.core.resolver;

import java.util.Objects;
import java.util.Optional;

/**
 * Maven artifact coordinate (groupId:artifactId[:version]).
 *
 * <p>Version is optional at the user-input layer; the resolver fills it in
 * from {@code maven-metadata.xml} when the user omits it.</p>
 */
public record MavenCoord(String groupId, String artifactId, Optional<String> version) {

    public MavenCoord {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(version, "version");
        if (groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be blank");
        }
        if (artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId must not be blank");
        }
    }

    public static MavenCoord of(String groupId, String artifactId, String version) {
        return new MavenCoord(groupId, artifactId, Optional.of(version));
    }

    public static MavenCoord of(String groupId, String artifactId) {
        return new MavenCoord(groupId, artifactId, Optional.empty());
    }

    /**
     * Parses {@code groupId:artifactId[:version]}. Returns empty on malformed input.
     */
    public static Optional<MavenCoord> parse(String spec) {
        if (spec == null) {
            return Optional.empty();
        }
        String[] parts = spec.split(":");
        if (parts.length == 2) {
            if (parts[0].isBlank() || parts[1].isBlank()) {
                return Optional.empty();
            }
            return Optional.of(of(parts[0], parts[1]));
        }
        if (parts.length == 3) {
            if (parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                return Optional.empty();
            }
            return Optional.of(of(parts[0], parts[1], parts[2]));
        }
        return Optional.empty();
    }

    /**
     * Returns {@code groupId:artifactId} regardless of whether a version is set.
     */
    public String ga() {
        return groupId + ":" + artifactId;
    }

    /**
     * Returns the canonical Maven directory path for this coordinate's group +
     * artifact, omitting the version. Always uses {@code /} as separator.
     */
    public String groupPath() {
        return groupId.replace('.', '/') + "/" + artifactId;
    }

    /**
     * Returns the Maven directory path for a specific version. Always uses
     * {@code /} as separator; suitable for URI construction.
     */
    public String versionPath(String resolvedVersion) {
        Objects.requireNonNull(resolvedVersion, "resolvedVersion");
        return groupPath() + "/" + resolvedVersion;
    }

    /**
     * Returns the JAR file name for a specific version (no path prefix).
     */
    public String jarFileName(String resolvedVersion) {
        Objects.requireNonNull(resolvedVersion, "resolvedVersion");
        return artifactId + "-" + resolvedVersion + ".jar";
    }

    @Override
    public String toString() {
        return version.map(v -> ga() + ":" + v).orElseGet(this::ga);
    }
}
