package com.botwithus.bot.core.runlog;

import com.botwithus.bot.core.shm.Layout;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The header values that describe this host process rather than a script:
 * {@code host_version}, {@code protocol_version}, {@code os} and {@code runtime}.
 *
 * @param hostVersion     the host build's version, or {@code unknown}
 * @param protocolVersion the wire protocol this host speaks; the agent must match it
 * @param os              operating system name and version
 * @param runtime         {@code Java <feature.interim.update>}
 */
public record HostIdentity(String hostVersion, int protocolVersion, String os, String runtime) {

    /**
     * This process's identity. The host version is the module version Gradle
     * compiles into {@code versionAnchor}'s module ({@code -PreleaseVersion} on a
     * release, {@code 1.0-SNAPSHOT} on a local build), else the jar's
     * {@code Implementation-Version}, else {@code unknown}.
     *
     * <p>Java reports Windows as {@code 10.0} with no build number, so {@code os}
     * reads e.g. {@code Windows 11 10.0}.</p>
     */
    public static HostIdentity current(Class<?> versionAnchor) {
        String version = versionOf(versionAnchor);
        String os = System.getProperty("os.name", RunLogHeader.UNKNOWN) + " "
                + System.getProperty("os.version", "");
        String runtime = "Java " + Runtime.version().version().stream()
                .map(String::valueOf).collect(Collectors.joining("."));
        return new HostIdentity(version == null ? RunLogHeader.UNKNOWN : version,
                Layout.PROTOCOL_VERSION, os.strip(), runtime);
    }

    private static String versionOf(Class<?> anchor) {
        Module module = anchor.getModule();
        if (module.isNamed() && module.getDescriptor() != null) {
            Optional<String> raw = module.getDescriptor().rawVersion();
            if (raw.isPresent()) {
                return raw.get();
            }
        }
        Package pkg = anchor.getPackage();
        return pkg != null ? pkg.getImplementationVersion() : null;
    }
}
