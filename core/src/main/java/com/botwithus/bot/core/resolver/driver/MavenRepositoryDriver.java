package com.botwithus.bot.core.resolver.driver;

import com.botwithus.bot.core.resolver.Credentials;
import com.botwithus.bot.core.resolver.MavenCoord;
import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.SearchOutcome;
import com.botwithus.bot.core.resolver.driver.MavenMetadataParser.MetadataParseException;
import com.botwithus.bot.core.resolver.transport.Transport;
import com.botwithus.bot.core.resolver.transport.TransportResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Standard Maven repository layout driver. Implements the
 * {@code maven-metadata.xml} version index and the canonical
 * {@code groupId/artifactId/version/artifactId-version.<ext>} URL shape
 * used by repo1.maven.org, Nexus, JitPack, Artifactory, etc.
 *
 * <p>Registered as the {@code "maven"} driver via
 * {@link java.util.ServiceLoader} (see the service-registration file
 * under {@code META-INF/services/}). Stateless — one shared instance per
 * resolver. {@link MavenMetadataParser} is similarly stateless and a
 * private implementation detail.</p>
 */
public final class MavenRepositoryDriver implements RepositoryDriver {

    private static final Logger log = LoggerFactory.getLogger(MavenRepositoryDriver.class);

    /** {@link Repository#driverId()} value that selects this driver. */
    public static final String TYPE_ID = "maven";

    private static final String METADATA_FILENAME = "maven-metadata.xml";
    private static final String JAR_EXTENSION = ".jar";
    private static final String SHA256_SUFFIX = ".sha256";
    private static final String SHA1_SUFFIX = ".sha1";
    private static final String ASC_SUFFIX = ".asc";
    private static final String TMP_PREFIX = "maven-metadata-";
    private static final String CENTRAL_HOST = "search.maven.org";
    private static final int DEFAULT_SEARCH_LIMIT = 50;

    private final MavenMetadataParser parser;

    public MavenRepositoryDriver() {
        this(new MavenMetadataParser());
    }

    MavenRepositoryDriver(MavenMetadataParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    @Override
    public String typeId() {
        return TYPE_ID;
    }

    @Override
    public CompletableFuture<ListVersionsResult> listVersions(
            Repository repository, MavenCoord coord, Transport transport, Optional<Credentials> credentials) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(coord, "coord");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(credentials, "credentials");

        URI metadataUri = repository.url().resolve(coord.groupPath() + "/" + METADATA_FILENAME);
        Path tmp;
        try {
            tmp = Files.createTempFile(TMP_PREFIX, ".xml");
        } catch (IOException e) {
            return CompletableFuture.completedFuture(new ListVersionsResult.TransportFailed(
                    new TransportResult.Network(metadataUri.toString(), e)));
        }
        return transport.fetch(metadataUri, tmp, credentials)
                .thenApply(result -> liftFetch(result, metadataUri, tmp));
    }

    private ListVersionsResult liftFetch(TransportResult result, URI metadataUri, Path tmp) {
        try {
            return switch (result) {
                case TransportResult.Ok ok -> parseListing(ok.localPath());
                case TransportResult.NotFound nf -> new ListVersionsResult.NotIndexed("no metadata at " + metadataUri);
                case TransportResult.HttpError he -> new ListVersionsResult.TransportFailed(he);
                case TransportResult.Network n -> new ListVersionsResult.TransportFailed(n);
            };
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                log.debug("failed to delete metadata temp {}", tmp, e);
            }
        }
    }

    private ListVersionsResult parseListing(Path file) {
        try {
            MavenMetadata md = parser.parse(file);
            VersionListing listing = new VersionListing(md.bestRelease(), md.versions());
            return new ListVersionsResult.Ok(listing);
        } catch (IOException | MetadataParseException e) {
            log.debug("metadata parse failure for {}", file, e);
            return new ListVersionsResult.Malformed("malformed metadata: " + e.getMessage());
        }
    }

    @Override
    public ArtifactLocation locateJar(Repository repository, MavenCoord coord, String version) {
        return new ArtifactLocation.Url(
                repository.url().resolve(coord.versionPath(version) + "/" + jarFileName(coord, version)));
    }

    @Override
    public ArtifactLocation locateChecksum(Repository repository, MavenCoord coord, String version) {
        return new ArtifactLocation.Url(sidecarUri(repository, coord, version, SHA256_SUFFIX));
    }

    @Override
    public ArtifactLocation locateLegacyChecksum(Repository repository, MavenCoord coord, String version) {
        return new ArtifactLocation.Url(sidecarUri(repository, coord, version, SHA1_SUFFIX));
    }

    @Override
    public ArtifactLocation locateSignature(Repository repository, MavenCoord coord, String version) {
        return new ArtifactLocation.Url(sidecarUri(repository, coord, version, ASC_SUFFIX));
    }

    @Override
    public Optional<SearchProtocol> search(Repository repository) {
        Objects.requireNonNull(repository, "repository");
        return repository.searchEndpoint().map(endpoint -> {
            boolean central = isCentralStyle(endpoint);
            SearchProtocol.RequestBuilder builder = central
                    ? MavenRepositoryDriver::buildCentralUri
                    : MavenRepositoryDriver::buildNexusUri;
            return new SearchProtocol(endpoint, builder,
                    central ? MavenRepositoryDriver::parseCentralHits : MavenRepositoryDriver::parseNexusHits);
        });
    }

    private static URI sidecarUri(Repository repository, MavenCoord coord, String version, String suffix) {
        return repository.url().resolve(coord.versionPath(version) + "/" + jarFileName(coord, version) + suffix);
    }

    private static String jarFileName(MavenCoord coord, String version) {
        return coord.artifactId() + "-" + version + JAR_EXTENSION;
    }

    // === Search dialect plumbing =========================================
    //
    // Maven Central serves a Solr-style endpoint; Nexus REST v1 has its
    // own shape. Both are pure URL-builder + JSON-parser pairs.

    private static boolean isCentralStyle(URI endpoint) {
        String host = endpoint.getHost();
        return host != null && host.toLowerCase(Locale.ROOT).contains(CENTRAL_HOST);
    }

    private static URI buildCentralUri(URI endpoint, String query, int limit) {
        int rows = limit > 0 ? limit : DEFAULT_SEARCH_LIMIT;
        String suffix = "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&rows=" + rows
                + "&wt=json";
        return URI.create(endpoint + suffix);
    }

    private static URI buildNexusUri(URI endpoint, String query, int limit) {
        String suffix = "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        if (limit > 0) {
            suffix += "&limit=" + limit;
        }
        return URI.create(endpoint + suffix);
    }

    private static List<SearchOutcome.Hit> parseCentralHits(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return List.of();
            }
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("response")) {
                return List.of();
            }
            JsonObject response = root.getAsJsonObject("response");
            if (!response.has("docs")) {
                return List.of();
            }
            return extractHits(response.getAsJsonArray("docs"), "g", "a", "latestVersion", "p");
        } catch (JsonSyntaxException e) {
            return List.of();
        }
    }

    private static List<SearchOutcome.Hit> parseNexusHits(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return List.of();
            }
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("items")) {
                return List.of();
            }
            return extractHits(root.getAsJsonArray("items"), "group", "name", "version", null);
        } catch (JsonSyntaxException e) {
            return List.of();
        }
    }

    private static List<SearchOutcome.Hit> extractHits(JsonArray items, String groupKey, String artifactKey,
                                                       String versionKey, String descKey) {
        List<SearchOutcome.Hit> hits = new ArrayList<>();
        for (JsonElement el : items) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            String g = optString(obj, groupKey);
            String a = optString(obj, artifactKey);
            String v = optString(obj, versionKey);
            String desc = descKey != null ? optString(obj, descKey) : "";
            if (g.isEmpty() || a.isEmpty()) {
                continue;
            }
            hits.add(new SearchOutcome.Hit(MavenCoord.of(g, a), v, desc));
        }
        return hits;
    }

    private static String optString(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return "";
        }
        JsonElement e = o.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
