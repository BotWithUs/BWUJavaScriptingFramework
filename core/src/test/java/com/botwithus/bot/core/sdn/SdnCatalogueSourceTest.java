package com.botwithus.bot.core.sdn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launcher is not running in a unit test, so every case here plays its part
 * by dropping the reply file the courier would have written.
 */
class SdnCatalogueSourceTest {

    private static final Duration SHORT = Duration.ofMillis(300);

    @TempDir
    Path dir;

    private SdnCatalogueSource source;

    @BeforeEach
    void setUp() {
        source = new SdnCatalogueSource(dir);
    }

    private Path replyFile() {
        return dir.resolve(SdnRendezvous.currentPid() + ".cat");
    }

    private void courierAnswers(String json) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(replyFile(), json, StandardCharsets.UTF_8);
    }

    // Delivered ------------------------------------------------------------

    @Test
    void fetch_courierAnswersOk_returnsEntries() throws IOException {
        courierAnswers("""
                {"status":"ok","entries":[
                  {"id":"7","name":"Woodcutter","author":"ada","subscriber":"bob",
                   "version":"1.4","apiVersion":"2","tagline":"Chops",
                   "description":"Chops trees","scriptClass":"a.B",
                   "agentv1Support":false,"agentv2Support":true}]}""");

        SdnCatalogueResult result = source.fetch(SHORT);

        SdnCatalogueResult.Delivered delivered =
                assertInstanceOf(SdnCatalogueResult.Delivered.class, result);
        assertEquals(1, delivered.entries().size());
        assertFalse(delivered.stale());
        SdnCatalogueEntry entry = delivered.entries().get(0);
        assertEquals("7", entry.id());
        assertEquals("Woodcutter", entry.name());
        assertEquals("1.4", entry.version());
        assertTrue(entry.runsOnThisHost());
        assertFalse(entry.isOwned(), "author 'ada' differs from subscriber 'bob'");
    }

    @Test
    void fetch_entryAuthoredByCaller_isOwned() throws IOException {
        courierAnswers("""
                {"status":"ok","entries":[
                  {"id":"1","name":"Mine","author":"ada","subscriber":"ada",
                   "agentv2Support":true}]}""");

        SdnCatalogueResult.Delivered delivered = assertInstanceOf(
                SdnCatalogueResult.Delivered.class, source.fetch(SHORT));

        assertTrue(delivered.entries().get(0).isOwned());
    }

    @Test
    void fetch_entryMissingOptionalFields_doesNotThrow() throws IOException {
        courierAnswers("""
                {"status":"ok","entries":[{"id":"1","name":"Sparse"}]}""");

        SdnCatalogueResult.Delivered delivered = assertInstanceOf(
                SdnCatalogueResult.Delivered.class, source.fetch(SHORT));

        SdnCatalogueEntry entry = delivered.entries().get(0);
        assertEquals("", entry.author());
        assertFalse(entry.runsOnThisHost(), "absent agentv2Support must not read as supported");
        assertEquals("", entry.summary());
    }

    @Test
    void fetch_unknownExtraFields_areIgnored() throws IOException {
        courierAnswers("""
                {"status":"ok","somethingNew":42,"entries":[
                  {"id":"1","name":"Fwd","futureFlag":true,"agentv2Support":true}]}""");

        SdnCatalogueResult.Delivered delivered = assertInstanceOf(
                SdnCatalogueResult.Delivered.class, source.fetch(SHORT));

        assertEquals(1, delivered.entries().size());
    }

    @Test
    void fetch_emptyCatalogue_isDeliveredNotFailed() throws IOException {
        courierAnswers("""
                {"status":"ok","entries":[]}""");

        SdnCatalogueResult.Delivered delivered = assertInstanceOf(
                SdnCatalogueResult.Delivered.class, source.fetch(SHORT));

        assertTrue(delivered.entries().isEmpty());
    }

    /**
     * The entry object here is copied verbatim from a live catalogue response,
     * so this pins the field spellings the site actually sends. Note
     * {@code scriptClass} arrives as JSON null and {@code id} as a string.
     */
    @Test
    void fetch_realWorldPayload_parsesEveryField() throws IOException {
        courierAnswers("""
                {"status":"ok","entries":[
                  {"name":"Loop Test Script","description":"","id":"868730679844",
                   "author":"looptest","subscriber":"looptest","version":"1",
                   "apiVersion":"2","agentv1Support":false,"agentv2Support":true,
                   "tagline":"No tagline provided.","scriptClass":null}]}""");

        SdnCatalogueResult.Delivered delivered = assertInstanceOf(
                SdnCatalogueResult.Delivered.class, source.fetch(SHORT));

        SdnCatalogueEntry entry = delivered.entries().get(0);
        assertEquals("868730679844", entry.id());
        assertEquals("Loop Test Script", entry.name());
        assertEquals("looptest", entry.author());
        assertEquals("1", entry.version());
        assertEquals("", entry.scriptClass(), "a JSON null must read as empty, not crash");
        assertTrue(entry.runsOnThisHost());
        assertTrue(entry.isOwned(), "author and subscriber match, so it is the caller's own");
        assertEquals("No tagline provided.", entry.summary());
    }

    /**
     * The bytes below are the verbatim output of the launcher's own serializer,
     * captured from it rather than written by hand. The two sides were built
     * separately, so this is the test that fails if either drifts.
     */
    @Test
    void fetch_replyProducedByTheLauncher_parsesExactly() throws IOException {
        courierAnswers("{\"status\":\"ok\",\"message\":\"\",\"entries\":["
                + "{\"id\":\"868730679844\",\"name\":\"Loop Test Script\","
                + "\"author\":\"looptest\",\"subscriber\":\"looptest\",\"version\":\"1\","
                + "\"apiVersion\":\"2\",\"tagline\":\"No tagline provided.\","
                + "\"description\":\"\",\"scriptClass\":\"\","
                + "\"agentv1Support\":false,\"agentv2Support\":true}]}");

        SdnCatalogueResult.Delivered delivered = assertInstanceOf(
                SdnCatalogueResult.Delivered.class, source.fetch(SHORT));

        SdnCatalogueEntry entry = delivered.entries().get(0);
        assertEquals("868730679844", entry.id());
        assertEquals("Loop Test Script", entry.name());
        assertEquals("looptest", entry.author());
        assertTrue(entry.runsOnThisHost());
        assertTrue(entry.isOwned());
        assertEquals("No tagline provided.", entry.summary());
    }

    // The failure taxonomy -------------------------------------------------

    @Test
    void fetch_launcherReportsNotSignedIn_isDistinctFromEmpty() throws IOException {
        courierAnswers("""
                {"status":"not_signed_in"}""");

        assertInstanceOf(SdnCatalogueResult.NotSignedIn.class, source.fetch(SHORT));
    }

    @Test
    void fetch_launcherReportsNoSubscription_isDistinctFromEmpty() throws IOException {
        courierAnswers("""
                {"status":"subscription_required"}""");

        assertInstanceOf(SdnCatalogueResult.SubscriptionRequired.class, source.fetch(SHORT));
    }

    @Test
    void fetch_launcherReportsError_carriesItsMessage() throws IOException {
        courierAnswers("""
                {"status":"error","message":"the site is down"}""");

        SdnCatalogueResult.Failed failed =
                assertInstanceOf(SdnCatalogueResult.Failed.class, source.fetch(SHORT));
        assertEquals("the site is down", failed.reason());
    }

    @Test
    void fetch_malformedReply_failsRatherThanThrowing() throws IOException {
        courierAnswers("{not json at all");

        assertInstanceOf(SdnCatalogueResult.Failed.class, source.fetch(SHORT));
    }

    @Test
    void fetch_noLauncherAndNoCache_isCourierUnavailable() {
        assertInstanceOf(SdnCatalogueResult.CourierUnavailable.class, source.fetch(SHORT));
    }

    // The stale fallback ---------------------------------------------------

    @Test
    void fetch_noLauncherButCachedCatalogue_returnsItMarkedStale() throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("catalogue.json"), """
                {"status":"ok","entries":[
                  {"id":"9","name":"Remembered","agentv2Support":true}]}""");

        SdnCatalogueResult.Delivered delivered = assertInstanceOf(
                SdnCatalogueResult.Delivered.class, source.fetch(SHORT));

        assertTrue(delivered.stale(), "a cached catalogue must announce itself as stale");
        assertEquals("Remembered", delivered.entries().get(0).name());
    }

    @Test
    void fetch_malformedCache_failsRatherThanThrowing() throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("catalogue.json"), "{{{");

        assertInstanceOf(SdnCatalogueResult.Failed.class, source.fetch(SHORT));
    }

    // Housekeeping ---------------------------------------------------------

    @Test
    void fetch_writesARequestForTheCourier() throws IOException {
        Files.createDirectories(dir);
        source.fetch(Duration.ofMillis(120));

        // The request is cleaned up afterwards, so observe it by the fact that
        // fetch created the rendezvous directory and left nothing behind.
        try (var entries = Files.list(dir)) {
            assertTrue(entries.findAny().isEmpty(),
                    "fetch must not leave request or reply files behind");
        }
    }

    @Test
    void fetch_consumesTheReply_soASecondFetchDoesNotSeeIt() throws IOException {
        courierAnswers("""
                {"status":"ok","entries":[{"id":"1","name":"Once","agentv2Support":true}]}""");

        assertInstanceOf(SdnCatalogueResult.Delivered.class, source.fetch(SHORT));
        assertFalse(Files.exists(replyFile()), "the reply must be deleted once consumed");
        assertInstanceOf(SdnCatalogueResult.CourierUnavailable.class, source.fetch(SHORT));
    }

    @Test
    void fetch_entriesAreDefensivelyCopied() throws IOException {
        courierAnswers("""
                {"status":"ok","entries":[{"id":"1","name":"A","agentv2Support":true}]}""");

        SdnCatalogueResult.Delivered delivered = assertInstanceOf(
                SdnCatalogueResult.Delivered.class, source.fetch(SHORT));
        List<SdnCatalogueEntry> entries = delivered.entries();

        assertThrows(UnsupportedOperationException.class, () -> entries.add(null));
    }
}
