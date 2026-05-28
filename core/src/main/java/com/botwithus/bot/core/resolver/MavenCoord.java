package com.botwithus.bot.core.resolver;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Maven artifact coordinate (groupId:artifactId[:version]).
 *
 * <p>Version is optional at the user-input layer; the resolver fills it in
 * from {@code maven-metadata.xml} when the user omits it.</p>
 */
public record MavenCoord(String groupId, String artifactId, Optional<String> version) {

    // Each part is interpolated into filesystem paths (jarFileName /
    // versionPath → scriptsDir.resolve). Restricting to this charset and
    // rejecting ".." keeps a malicious repository's metadata version (or a
    // crafted spec) from escaping the scripts directory via path traversal.
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._+-]+");

    public MavenCoord {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(version, "version");
        if (!isValidToken(groupId)) {
            throw new IllegalArgumentException("invalid groupId: " + groupId);
        }
        if (!isValidToken(artifactId)) {
            throw new IllegalArgumentException("invalid artifactId: " + artifactId);
        }
        if (version.isPresent() && !isValidToken(version.get())) {
            throw new IllegalArgumentException("invalid version: " + version.get());
        }
    }

    /**
     * Whether {@code token} is a safe groupId / artifactId / version
     * component: non-blank, drawn only from {@code [A-Za-z0-9._+-]}, and
     * free of {@code ".."}. Path separators are already excluded by the
     * charset; the {@code ".."} guard is belt-and-suspenders against
     * traversal.
     */
    public static boolean isValidToken(String token) {
        return token != null
                && !token.isBlank()
                && SAFE_TOKEN.matcher(token).matches()
                && !token.contains("..");
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
        try {
            if (parts.length == 2) {
                return Optional.of(of(parts[0], parts[1]));
            }
            if (parts.length == 3) {
                return Optional.of(of(parts[0], parts[1], parts[2]));
            }
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
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
