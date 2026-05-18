package com.botwithus.bot.core.resolver.pipeline;

import com.botwithus.bot.core.resolver.Credentials;
import com.botwithus.bot.core.resolver.MavenCoord;
import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.transport.MavenTransport;
import com.botwithus.bot.core.resolver.transport.TransportResult;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the URIs and fires the transport for the four pieces of one
 * resolved version: jar, sha256 sidecar, and (when policy demands it) asc.
 *
 * <p>Stateless coordinator — owns no IO state of its own. The actual byte
 * transfer is delegated to {@link MavenTransport}.</p>
 */
public final class ArtifactDownloader {

    private static final String JAR_EXTENSION = ".jar";
    private static final String SHA256_SUFFIX = ".sha256";
    private static final String ASC_SUFFIX = ".asc";

    private final MavenTransport transport;

    public ArtifactDownloader(MavenTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public TransportResult fetchJar(MavenCoord coord, String version, Repository repository, Path stagingDir,
                                    Optional<Credentials> credentials) {
        URI uri = jarUri(coord, version, repository);
        Path dest = stagingDir.resolve(coord.jarFileName(version));
        return transport.fetch(uri, dest, credentials).join();
    }

    public TransportResult fetchSha256(MavenCoord coord, String version, Repository repository, Path stagingDir,
                                       Optional<Credentials> credentials) {
        URI uri = jarUri(coord, version, repository).resolve(jarFileName(coord, version) + SHA256_SUFFIX);
        Path dest = stagingDir.resolve(coord.jarFileName(version) + SHA256_SUFFIX);
        return transport.fetch(uri, dest, credentials).join();
    }

    public TransportResult fetchAsc(MavenCoord coord, String version, Repository repository, Path stagingDir,
                                    Optional<Credentials> credentials) {
        URI uri = jarUri(coord, version, repository).resolve(jarFileName(coord, version) + ASC_SUFFIX);
        Path dest = stagingDir.resolve(coord.jarFileName(version) + ASC_SUFFIX);
        return transport.fetch(uri, dest, credentials).join();
    }

    private static URI jarUri(MavenCoord coord, String version, Repository repository) {
        return repository.url().resolve(coord.versionPath(version) + "/" + jarFileName(coord, version));
    }

    private static String jarFileName(MavenCoord coord, String version) {
        return coord.artifactId() + "-" + version + JAR_EXTENSION;
    }
}
