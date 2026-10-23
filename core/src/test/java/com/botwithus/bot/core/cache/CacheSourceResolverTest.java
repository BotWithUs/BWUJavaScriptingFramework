package com.botwithus.bot.core.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the precedence a shipped host resolves its cache source by, and the
 * predicate discovery gates on.
 *
 * <p>Every case injects its own property reader and candidate list, so nothing
 * here reads or mutates process-global state and the suite is order
 * independent. That is the reason {@link CacheSourceResolver} takes both as
 * constructor arguments rather than reading {@code System} directly.</p>
 */
class CacheSourceResolverTest {

    private static final Map<String, String> NO_PROPERTIES = Map.of();

    /** An id no real cache would use; only its name matters to the glob. */
    private static final String AN_INDEX_FILE = "js5-7.jcache";

    private static CacheSourceResolver resolver(List<Path> candidates, Map<String, String> properties) {
        UnaryOperator<String> reader = properties::get;
        return new CacheSourceResolver(candidates, reader);
    }

    /** Resolver whose running-client tier answers with {@code fromClient}. */
    private static CacheSourceResolver resolver(List<Path> candidates, Map<String, String> properties,
                                                Path fromClient) {
        UnaryOperator<String> reader = properties::get;
        return new CacheSourceResolver(candidates, reader, () -> Optional.ofNullable(fromClient));
    }

    // ------------------------------------------------------- The predicate

    @Test
    void aDirectoryHoldingAnIndexFileIsAGameCache(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve(AN_INDEX_FILE));
        assertTrue(CacheSourceResolver.holdsGameCache(dir));
    }

    @Test
    void anEmptyDirectoryIsNotAGameCache(@TempDir Path dir) {
        assertFalse(CacheSourceResolver.holdsGameCache(dir),
                "existence alone must not qualify — the Jagex launcher creates this "
                        + "directory before any archive is downloaded");
    }

    @Test
    void aDirectoryOfOnlyClientSettingsIsNotAGameCache(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("GlobalSettings.jcache"));
        Files.createFile(dir.resolve("ObjCache.jcache"));
        Files.createFile(dir.resolve("ShaderManager.jcache"));
        assertFalse(CacheSourceResolver.holdsGameCache(dir),
                "these three sit beside the real cache and carry no decodable archive");
    }

    @Test
    void anAbsentDirectoryIsNotAGameCache(@TempDir Path dir) {
        assertFalse(CacheSourceResolver.holdsGameCache(dir.resolve("never-created")));
    }

    // ------------------------------------------------------- The precedence

    @Test
    void thePathOverrideWinsOverAPopulatedCandidate(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve(AN_INDEX_FILE));
        Path named = dir.resolve("somewhere-else");

        CacheSource source = resolver(List.of(dir),
                Map.of(CacheSourceResolver.PATH_PROPERTY, named.toString())).resolve();

        assertEquals(new CacheSource.LocalDirectory(named, true), source,
                "the dev override must beat discovery, or a dev cannot point the host "
                        + "at a patched cache any more");
    }

    @Test
    void thePathOverrideIsTakenAtItsWordEvenWhenItHoldsNoCache(@TempDir Path dir) {
        Path empty = dir.resolve("empty-but-named");

        CacheSource source = resolver(List.of(),
                Map.of(CacheSourceResolver.PATH_PROPERTY, empty.toString())).resolve();

        assertEquals(new CacheSource.LocalDirectory(empty, true), source,
                "a typo'd override must reach the open and fail with the operator's own "
                        + "path in the message, not degrade to the network");
    }

    @Test
    void aBlankPathOverrideIsIgnored(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve(AN_INDEX_FILE));

        CacheSource source = resolver(List.of(dir),
                Map.of(CacheSourceResolver.PATH_PROPERTY, "   ")).resolve();

        assertEquals(new CacheSource.LocalDirectory(dir, false), source);
    }

    @Test
    void theLiveOverrideWinsOverAPopulatedCandidate(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve(AN_INDEX_FILE));

        CacheSource source = resolver(List.of(dir),
                Map.of(CacheSourceResolver.LIVE_PROPERTY, "true")).resolve();

        assertEquals(new CacheSource.Live(true), source);
    }

    @Test
    void thePathOverrideWinsOverTheLiveOverride(@TempDir Path dir) {
        CacheSource source = resolver(List.of(), Map.of(
                CacheSourceResolver.PATH_PROPERTY, dir.toString(),
                CacheSourceResolver.LIVE_PROPERTY, "true")).resolve();

        assertEquals(new CacheSource.LocalDirectory(dir, true), source);
    }

    @Test
    void aPopulatedCandidateIsDiscoveredWithNoPropertySet(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve(AN_INDEX_FILE));

        CacheSource source = resolver(List.of(dir), NO_PROPERTIES).resolve();

        assertEquals(new CacheSource.LocalDirectory(dir, false), source,
                "this is the whole point: a shipped install with no -D flag");
    }

    @Test
    void discoveryTakesTheFirstCandidateThatHoldsACache(@TempDir Path dir) throws IOException {
        Path bare = Files.createDirectory(dir.resolve("bare"));
        Path populated = Files.createDirectory(dir.resolve("populated"));
        Files.createFile(populated.resolve(AN_INDEX_FILE));

        CacheSource source = resolver(List.of(dir.resolve("absent"), bare, populated), NO_PROPERTIES)
                .resolve();

        assertEquals(new CacheSource.LocalDirectory(populated, false), source);
    }

    // --------------------------------------------- The running-client tier

    @Test
    void theRunningClientBeatsAPopulatedCandidate(@TempDir Path dir) throws IOException {
        Path candidate = Files.createDirectory(dir.resolve("a-known-install-location"));
        Files.createFile(candidate.resolve(AN_INDEX_FILE));
        Path fromClient = Files.createDirectory(dir.resolve("where-the-client-actually-reads"));
        Files.createFile(fromClient.resolve(AN_INDEX_FILE));

        CacheSource source = resolver(List.of(candidate), NO_PROPERTIES, fromClient).resolve();

        assertEquals(new CacheSource.LocalDirectory(fromClient, false), source,
                "a user who relocated their cache has a populated cache at a location the "
                        + "candidate list also matches; the client's own answer is the only "
                        + "one that stays right, so it must win");
    }

    @Test
    void aRunningClientThatAnswersNothingFallsThroughToTheCandidates(@TempDir Path dir)
            throws IOException {
        Files.createFile(dir.resolve(AN_INDEX_FILE));

        CacheSource source = resolver(List.of(dir), NO_PROPERTIES, null).resolve();

        assertEquals(new CacheSource.LocalDirectory(dir, false), source,
                "the pid tier can be present but fruitless — an exited client, an "
                        + "unreadable exe path, a cache_folder naming a deleted directory — "
                        + "and the candidate list is what covers that failure");
    }

    @Test
    void thePathOverrideWinsOverTheRunningClient(@TempDir Path dir) throws IOException {
        Path fromClient = Files.createDirectory(dir.resolve("the-clients-own-cache"));
        Files.createFile(fromClient.resolve(AN_INDEX_FILE));
        Path named = dir.resolve("what-the-dev-asked-for");

        CacheSource source = resolver(List.of(), Map.of(
                CacheSourceResolver.PATH_PROPERTY, named.toString()), fromClient).resolve();

        assertEquals(new CacheSource.LocalDirectory(named, true), source,
                "an explicit override still beats the authoritative answer — a dev "
                        + "pointing at a patched cache outranks the running client");
    }

    @Test
    void theLiveOverrideWinsOverTheRunningClient(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve(AN_INDEX_FILE));

        CacheSource source = resolver(List.of(), Map.of(
                CacheSourceResolver.LIVE_PROPERTY, "true"), dir).resolve();

        assertEquals(new CacheSource.Live(true), source);
    }

    @Test
    void anOverrideResolvesWithoutConsultingTheRunningClient(@TempDir Path dir) {
        AtomicBoolean consulted = new AtomicBoolean();
        CacheSourceResolver resolver = new CacheSourceResolver(List.of(),
                Map.of(CacheSourceResolver.PATH_PROPERTY, dir.toString())::get,
                () -> {
                    consulted.set(true);
                    return Optional.of(dir);
                });

        resolver.resolve();

        assertFalse(consulted.get(),
                "the running-client tier is a Supplier so that naming -Dnxtcache.path "
                        + "costs no process inspection; an eager lookup would reintroduce "
                        + "the side effect the override leg is meant to avoid");
    }

    @Test
    void noPropertyAndNoPopulatedCandidateFallsBackToLive(@TempDir Path dir) {
        CacheSource source = resolver(List.of(dir, dir.resolve("absent")), NO_PROPERTIES).resolve();

        assertEquals(new CacheSource.Live(false), source,
                "the fallback is silent to the script and must never be a null source");
    }
}
