package com.botwithus.bot.core.resolver.pipeline;

import com.botwithus.bot.core.resolver.Credentials;
import com.botwithus.bot.core.resolver.MavenCoord;
import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.metadata.MavenMetadata;
import com.botwithus.bot.core.resolver.metadata.MavenMetadataParser;
import com.botwithus.bot.core.resolver.metadata.MavenMetadataParser.MetadataParseException;
import com.botwithus.bot.core.resolver.transport.MavenTransport;
import com.botwithus.bot.core.resolver.transport.TransportResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a {@link MavenCoord} that has no version to a concrete version
 * by fetching {@code maven-metadata.xml} from the repository.
 */
public final class VersionResolver {

    private static final Logger log = LoggerFactory.getLogger(VersionResolver.class);
    private static final String METADATA_FILENAME = "maven-metadata.xml";

    private final MavenTransport transport;
    private final MavenMetadataParser parser;

    public VersionResolver(MavenTransport transport, MavenMetadataParser parser) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    /**
     * Picks a concrete version for {@code coord} against {@code repository},
     * downloading {@code maven-metadata.xml} into {@code stagingDir}. If
     * {@code coord} already carries a version, returns it unchanged (no
     * network round-trip).
     */
    public Result resolve(MavenCoord coord, Repository repository, Path stagingDir, Optional<Credentials> credentials) {
        Objects.requireNonNull(coord, "coord");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(stagingDir, "stagingDir");
        Objects.requireNonNull(credentials, "credentials");

        if (coord.version().isPresent()) {
            return new Result.Resolved(coord.version().get(), Optional.empty());
        }

        URI metadataUri = repository.url().resolve(coord.groupPath() + "/" + METADATA_FILENAME);
        Path destination = stagingDir.resolve(METADATA_FILENAME);
        TransportResult fetchResult;
        try {
            Files.createDirectories(stagingDir);
            fetchResult = transport.fetch(metadataUri, destination, credentials).join();
        } catch (IOException e) {
            return new Result.TransportFailed(new TransportResult.Network(metadataUri.toString(), e));
        }

        return switch (fetchResult) {
            case TransportResult.Ok ok -> parseMetadata(ok.localPath());
            case TransportResult.NotFound ignored -> new Result.NoMetadata(metadataUri);
            case TransportResult.HttpError httpError -> new Result.TransportFailed(httpError);
            case TransportResult.Network network -> new Result.TransportFailed(network);
        };
    }

    private Result parseMetadata(Path file) {
        try {
            MavenMetadata md = parser.parse(file);
            Optional<String> picked = md.bestRelease();
            if (picked.isEmpty()) {
                return new Result.NoVersionsListed(md);
            }
            return new Result.Resolved(picked.get(), Optional.of(md));
        } catch (IOException | MetadataParseException e) {
            log.debug("metadata parse failure for {}", file, e);
            return new Result.MetadataMalformed(e);
        }
    }

    /** Outcome of a version-resolution step. */
    public sealed interface Result {

        record Resolved(String version, Optional<MavenMetadata> metadata) implements Result {}

        record NoMetadata(URI url) implements Result {}

        record NoVersionsListed(MavenMetadata metadata) implements Result {}

        record MetadataMalformed(Exception cause) implements Result {}

        record TransportFailed(TransportResult cause) implements Result {}
    }
}
