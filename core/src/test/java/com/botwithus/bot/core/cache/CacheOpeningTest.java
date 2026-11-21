package com.botwithus.bot.core.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The open policy between the resolver and {@code NXTCache.dll}, driven with a
 * spy in place of the library. The spy is the whole native surface this
 * policy can reach — a local open — so every case here asserts what it was
 * asked to open, and the fail-closed cases assert it was asked nothing.
 */
class CacheOpeningTest {

    private static final String AN_INDEX_FILE = "js5-2.jcache";

    /** Records every local open it is asked for and answers with a marker handle. */
    private static final class SpyOpener implements CacheOpening.LocalOpener<String> {
        private final List<Path> opened = new ArrayList<>();

        @Override
        public String openLocal(Path directory) {
            opened.add(directory);
            return "handle:" + directory;
        }
    }

    private static CacheSource resolve(List<Path> candidates, Map<String, String> properties,
                                       Path fromClient) {
        return new CacheSourceResolver(candidates, properties::get,
                () -> Optional.ofNullable(fromClient)).resolve();
    }

    @Test
    void aLocalDirectoryIsOpenedLocallyOnce(@TempDir Path dir) throws IOException {
        SpyOpener spy = new SpyOpener();

        String handle = CacheOpening.open(new CacheSource.LocalDirectory(dir, false), spy);

        assertEquals("handle:" + dir, handle);
        assertEquals(List.of(dir), spy.opened);
    }

    @Test
    void anUnavailableSourceFailsClosedWithoutOpeningAnything() {
        SpyOpener spy = new SpyOpener();

        NoLocalCacheException thrown = assertThrows(NoLocalCacheException.class,
                () -> CacheOpening.open(new CacheSource.Unavailable("nothing here", false), spy));

        assertTrue(thrown.getMessage().contains("nothing here"), thrown.getMessage());
        assertEquals(List.of(), spy.opened);
    }

    @Test
    void aMachineWithNoLocalCacheGetsNoCacheAndNoOpen(@TempDir Path dir) {
        SpyOpener spy = new SpyOpener();
        CacheSource source = resolve(List.of(dir, dir.resolve("absent")), Map.of(), null);

        NoLocalCacheException thrown = assertThrows(NoLocalCacheException.class,
                () -> CacheOpening.open(source, spy));

        assertTrue(thrown.getMessage().startsWith("no local game cache"), thrown.getMessage());
        assertEquals(List.of(), spy.opened);
    }

    @Test
    void theLiveSwitchOpensNothingEvenWithEveryLocalTierAvailable(@TempDir Path dir)
            throws IOException {
        Files.createFile(dir.resolve(AN_INDEX_FILE));
        SpyOpener spy = new SpyOpener();
        CacheSource source = resolve(List.of(dir), Map.of(
                CacheSourceResolver.LIVE_PROPERTY, "true",
                CacheSourceResolver.PATH_PROPERTY, dir.toString()), dir);

        NoLocalCacheException thrown = assertThrows(NoLocalCacheException.class,
                () -> CacheOpening.open(source, spy));

        assertTrue(thrown.getMessage().contains(CacheSourceResolver.LIVE_PROPERTY),
                thrown.getMessage());
        assertEquals(List.of(), spy.opened);
    }

    @Test
    void aDiscoveredCacheIsTheOnlyThingOpened(@TempDir Path dir) throws IOException {
        Path populated = Files.createDirectories(dir.resolve("RuneScape"));
        Files.createFile(populated.resolve(AN_INDEX_FILE));
        SpyOpener spy = new SpyOpener();

        String handle = CacheOpening.open(
                resolve(List.of(dir.resolve("absent"), populated), Map.of(), null), spy);

        assertEquals(List.of(populated), spy.opened);
        assertEquals("handle:" + populated, handle);
    }
}
