package com.botwithus.bot.cli.clients;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonClientStoreTest {

    private static final String UUID = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_UUID = "fedcba9876543210fedcba9876543210";
    private static final int WORLD = 84;
    private static final Instant SEEN = Instant.parse("2026-09-26T12:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void whatIsSaved_isWhatIsLoaded_inOrder() throws IOException {
        JsonClientStore store = new JsonClientStore(tempDir.resolve("nested").resolve("clients.json"));
        List<RememberedClient> clients = List.of(
                new RememberedClient(UUID, Optional.of("Zezima"), OptionalInt.of(WORLD), SEEN),
                new RememberedClient(OTHER_UUID, Optional.empty(), OptionalInt.empty(), SEEN.plusSeconds(1)));

        store.save(clients);

        assertEquals(clients, new JsonClientStore(tempDir.resolve("nested").resolve("clients.json")).load());
    }

    @Test
    void savingAgain_replacesTheFile_andLeavesNoTemporaryFileBehind() throws IOException {
        Path file = tempDir.resolve("clients.json");
        JsonClientStore store = new JsonClientStore(file);
        store.save(List.of(new RememberedClient(UUID, Optional.of("Zezima"), OptionalInt.of(WORLD), SEEN)));

        store.save(List.of());

        assertEquals(List.of(), store.load());
        try (var files = Files.list(tempDir)) {
            assertEquals(List.of(file), files.toList());
        }
    }

    @Test
    void noFile_isNoClients() throws IOException {
        assertEquals(List.of(), new JsonClientStore(tempDir.resolve("clients.json")).load());
    }

    @Test
    void entriesWithoutARealAccount_areSkippedOnReading() throws IOException {
        Path file = tempDir.resolve("clients.json");
        Files.writeString(file, """
                {"version": 1, "clients": [
                  {"uuid": "dev_uuid", "lastSeenAt": "2026-09-26T12:00:00Z"},
                  {"uuid": "", "lastSeenAt": "2026-09-26T12:00:00Z"},
                  {"name": "no uuid", "lastSeenAt": "2026-09-26T12:00:00Z"},
                  {"uuid": "%s", "name": "Zezima", "lastWorld": 84, "lastSeenAt": "2026-09-26T12:00:00Z"}
                ]}
                """.formatted(UUID));

        assertEquals(List.of(new RememberedClient(UUID, Optional.of("Zezima"), OptionalInt.of(WORLD), SEEN)),
                new JsonClientStore(file).load());
    }

    @Test
    void anUnreadableFile_isSetAside_andReadAsEmpty() throws IOException {
        Path file = tempDir.resolve("clients.json");
        Files.writeString(file, "{ not json");

        assertEquals(List.of(), new JsonClientStore(file).load());

        assertTrue(Files.exists(tempDir.resolve("clients.json.corrupt")), "the bad file must be kept");
        assertTrue(Files.notExists(file));
    }

    @Test
    void aDevelopmentClient_cannotEvenBeExpressedAsRemembered() {
        assertThrows(IllegalArgumentException.class,
                () -> new RememberedClient("dev_uuid", Optional.empty(), OptionalInt.empty(), SEEN));
        assertThrows(IllegalArgumentException.class,
                () -> new RememberedClient("", Optional.empty(), OptionalInt.empty(), SEEN));
    }
}
