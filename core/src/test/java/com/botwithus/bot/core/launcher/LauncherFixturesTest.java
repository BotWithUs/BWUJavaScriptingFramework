package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.StopMode;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.msgpack.value.Value;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Holds this host's codec to the launcher's golden fixtures, byte for byte
 * (launcher ADR 0007, section 3.3, "Java round trip"), and holds the vendored
 * copy to the launcher sha it names.
 */
class LauncherFixturesTest {

    private static final LauncherFixtures FIXTURES = LauncherFixtures.load();
    private static final String MSGPACK = ".msgpack";
    private static final Pattern VENDOR_LINE = Pattern.compile("^([0-9a-f]{40})  (\\S+)$", Pattern.MULTILINE);

    /** Every fixture this host sends, and how this host's builder makes it from the manifest value. */
    private static final Map<String, Function<JsonObject, Value>> BUILDERS = builders();

    /**
     * Automation requests this host never sends. {@code hello.req} is the GUI's hello
     * ({@code clientKind:"gui"}). {@code hello.req.host.agentproto} is the native host's hello
     * ({@code hostKind:"native"}, with the optional {@code agentProtocol}). This host sends
     * {@code hello.req.host}.
     */
    private static final Set<String> NOT_SENT_BY_THIS_HOST =
            Set.of("hello.req", "hello.req.host.agentproto");

    @TestFactory
    Stream<DynamicTest> everyFixture_unpackThenRepack_isByteIdentical() {
        return FIXTURES.all().stream().map(f -> DynamicTest.dynamicTest(f.name(), () -> {
            byte[] repacked = LauncherRequests.pack(Envelope.decodeSingleValue(f.bytes()));
            assertSameBytes(f.name(), f.bytes(), repacked);
        }));
    }

    @TestFactory
    Stream<DynamicTest> everyFixture_decodesToItsManifestValue() {
        return FIXTURES.all().stream().map(f -> DynamicTest.dynamicTest(f.name(), () -> {
            assertEquals(f.expectedValue(), Envelope.decodeSingleValue(f.bytes()), f.name());
            assertSameBytes(f.name(), f.bytes(), LauncherRequests.pack(f.expectedValue()));
        }));
    }

    @TestFactory
    Stream<DynamicTest> everyRequestThisHostSends_isReproducedByItsBuilder() {
        return BUILDERS.entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey(), () -> {
            LauncherFixtures.Fixture fixture = FIXTURES.named(e.getKey());
            JsonObject envelope = fixture.value();
            byte[] built = LauncherRequests.encode(envelope.get("id").getAsLong(),
                    envelope.get("method").getAsString(), e.getValue().apply(fixture.body()));
            assertSameBytes(e.getKey(), fixture.bytes(), built);
        }));
    }

    @Test
    void builders_coverEveryAutomationRequestThisHostSends() {
        Set<String> sentMethods = Set.of(LauncherProtocol.METHOD_HELLO, LauncherProtocol.METHOD_SERVICE_STATUS,
                LauncherProtocol.METHOD_ACCOUNTS_LIST, LauncherProtocol.METHOD_CLIENT_LAUNCH,
                LauncherProtocol.METHOD_CLIENT_STOP, LauncherProtocol.METHOD_CLIENT_LIST,
                LauncherProtocol.METHOD_CLIENT_STATUS, LauncherProtocol.METHOD_EVENTS_SUBSCRIBE,
                LauncherProtocol.METHOD_HOST_ACK_CLOSE);
        List<String> uncovered = FIXTURES.all().stream()
                .filter(f -> f.direction().equals("c2s"))
                .filter(f -> sentMethods.contains(f.value().get("method").getAsString()))
                .filter(f -> f.surface().contains(LauncherProtocol.SURFACE_AUTOMATION))
                .filter(f -> !NOT_SENT_BY_THIS_HOST.contains(f.name()))
                .map(LauncherFixtures.Fixture::name)
                .filter(name -> !BUILDERS.containsKey(name))
                .toList();
        assertEquals(List.of(), uncovered, "requests this host sends with no builder check");
    }

    @Test
    void vendoredFiles_matchTheBlobIdsInVendorMd() throws Exception {
        Map<String, String> listed = vendorBlobIds();
        Path fixtures = FIXTURES.dir().resolve("fixtures");
        Set<String> present;
        try (Stream<Path> files = Files.list(fixtures)) {
            present = files.map(p -> p.getFileName().toString()).filter(name -> name.endsWith(MSGPACK))
                    .collect(Collectors.toSet());
        }
        assertEquals(listed.keySet(), present, "files present vs listed in VENDOR.md");
        for (Map.Entry<String, String> e : listed.entrySet()) {
            assertEquals(e.getValue(), gitBlobId(Files.readAllBytes(fixtures.resolve(e.getKey()))),
                    "blob id of " + e.getKey() + " (vendored from " + LauncherFixtures.SOURCE_SHA + ")");
        }
    }

    @Test
    void everyVendoredFile_hasAManifestEntry_andOnlyAutomationOnesAreVendored() {
        Set<String> listed = vendorBlobIds().keySet();
        Set<String> inManifest = FIXTURES.all().stream().map(LauncherFixtures.Fixture::file)
                .collect(Collectors.toSet());
        assertEquals(listed, inManifest, "VENDOR.md files vs manifest entries");
        assertEquals(List.of(), FIXTURES.all().stream()
                .filter(f -> !f.surface().contains(LauncherProtocol.SURFACE_AUTOMATION))
                .map(LauncherFixtures.Fixture::name).toList(), "this public repo vendors the automation surface only");
    }

    @Test
    void vendorMd_namesTheSourceSha() {
        String vendor = LauncherFixtures.read(FIXTURES.dir().resolve("VENDOR.md"));
        assertTrue(vendor.contains(LauncherFixtures.SOURCE_SHA), "VENDOR.md must name " + LauncherFixtures.SOURCE_SHA);
    }

    static Map<String, String> vendorBlobIds() {
        String vendor = LauncherFixtures.read(FIXTURES.dir().resolve("VENDOR.md"));
        Map<String, String> ids = new LinkedHashMap<>();
        Matcher m = VENDOR_LINE.matcher(vendor);
        while (m.find()) {
            ids.put(m.group(2), m.group(1));
        }
        return ids;
    }

    /** {@code git hash-object}: SHA-1 over {@code "blob <length>\0"} and the bytes. */
    static String gitBlobId(byte[] bytes) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(sha1.digest(bytes));
    }

    static void assertSameBytes(String name, byte[] expected, byte[] actual) {
        int offset = LauncherFixtures.firstDifference(expected, actual);
        if (offset >= 0) {
            fail(name + ": bytes differ first at offset " + offset + " (expected " + expected.length
                    + " bytes, got " + actual.length + ")\n expected " + HexFormat.of().formatHex(expected)
                    + "\n actual   " + HexFormat.of().formatHex(actual));
        }
    }

    private static Map<String, Function<JsonObject, Value>> builders() {
        Map<String, Function<JsonObject, Value>> b = new LinkedHashMap<>();
        b.put("hello.req.host", body -> LauncherRequests.hello(new HelloParams(
                body.get("clientVersion").getAsString(), body.get("hostPid").getAsLong(),
                Optional.of(body.get("hostLabel").getAsString()))));
        b.put("service.status.req", body -> Envelope.emptyBody());
        b.put("accounts.list.req", body -> Envelope.emptyBody());
        b.put("client.list.req", body -> Envelope.emptyBody());
        b.put("client.launch.req", body -> LauncherRequests.clientLaunch(body.get("accountId").getAsString(),
                body.get("characterIndex").getAsInt()));
        b.put("client.stop.req", body -> LauncherRequests.clientStop(body.get("clientId").getAsString(),
                stopMode(body.get("mode").getAsString())));
        b.put("client.status.req", body -> LauncherRequests.clientStatus(body.get("clientId").getAsString()));
        Function<JsonObject, Value> subscribe = body -> LauncherRequests.subscribe(
                body.getAsJsonArray("topics").asList().stream().map(JsonElement::getAsString).toList());
        b.put("events.subscribe.req", subscribe);
        b.put("events.subscribe.req.native", subscribe);
        b.put("host.ack_close.req", body -> LauncherRequests.ackClose(body.get("requestId").getAsLong(),
                decision(body.get("decision").getAsString())));
        b.put("host.ack_close.req.later", body -> LauncherRequests.ackClose(body.get("requestId").getAsLong(),
                decision(body.get("decision").getAsString())));
        return b;
    }

    private static StopMode stopMode(String wire) {
        return Stream.of(StopMode.values()).filter(m -> LauncherRequests.stopModeWire(m).equals(wire)).findFirst()
                .orElseThrow();
    }

    private static CloseDecision decision(String wire) {
        return Stream.of(CloseDecision.values()).filter(d -> d.wire().equals(wire)).findFirst().orElseThrow();
    }
}
