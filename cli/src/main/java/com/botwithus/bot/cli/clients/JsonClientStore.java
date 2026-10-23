package com.botwithus.bot.cli.clients;

import com.botwithus.bot.cli.AccountReply;
import com.botwithus.bot.core.config.AtomicFiles;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Keeps remembered clients in a small JSON file, {@code clients.json} in the
 * host's data folder.
 *
 * <p>Only clients on a real account are ever written; an entry that does not
 * name one is skipped on reading. Saves go through {@link AtomicFiles}, so a
 * crash mid-save leaves the previous file whole. A file that is not valid JSON
 * anyway is set aside as {@code <name>.corrupt}, logged, and read as empty, so it
 * never stops the host starting and the next save does not destroy the evidence.</p>
 */
public final class JsonClientStore implements ClientStore {

    private static final Logger log = LoggerFactory.getLogger(JsonClientStore.class);

    /** The file's name in the host's data folder. */
    public static final String FILE_NAME = "clients.json";

    /** Bumped when the file's shape changes in a way an older host cannot read. */
    private static final int FORMAT_VERSION = 1;
    private static final String VERSION = "version";
    private static final String CLIENTS = "clients";
    private static final String UUID = "uuid";
    private static final String NAME = "name";
    private static final String LAST_WORLD = "lastWorld";
    private static final String LAST_SEEN_AT = "lastSeenAt";
    private static final String CORRUPT_SUFFIX = ".corrupt";

    private final Path file;

    /** @param file the file to keep the clients in; its folder is created on the first save */
    public JsonClientStore(Path file) {
        this.file = file;
    }

    @Override
    public List<RememberedClient> load() throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        try {
            return parse(JsonParser.parseString(json));
        } catch (JsonParseException | IllegalStateException e) {
            setAside(e);
            return List.of();
        }
    }

    @Override
    public void save(List<RememberedClient> clients) throws IOException {
        JsonArray array = new JsonArray();
        clients.forEach(client -> array.add(toJson(client)));
        JsonObject root = new JsonObject();
        root.addProperty(VERSION, FORMAT_VERSION);
        root.add(CLIENTS, array);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        AtomicFiles.write(file, json.getBytes(StandardCharsets.UTF_8));
    }

    private static List<RememberedClient> parse(JsonElement root) {
        List<RememberedClient> clients = new ArrayList<>();
        JsonElement array = root.getAsJsonObject().get(CLIENTS);
        if (array == null) {
            return clients;
        }
        for (JsonElement element : array.getAsJsonArray()) {
            fromJson(element.getAsJsonObject()).ifPresent(clients::add);
        }
        return clients;
    }

    private static Optional<RememberedClient> fromJson(JsonObject object) {
        Optional<String> uuid = AccountReply.identified(stringOf(object, UUID).orElse(null));
        Optional<Instant> lastSeen = stringOf(object, LAST_SEEN_AT).flatMap(JsonClientStore::instantOf);
        if (uuid.isEmpty() || lastSeen.isEmpty()) {
            log.debug("Skipping a remembered client with no account uuid or last-seen time: {}", object);
            return Optional.empty();
        }
        JsonElement world = object.get(LAST_WORLD);
        OptionalInt lastWorld = world != null && world.isJsonPrimitive() && world.getAsJsonPrimitive().isNumber()
                ? OptionalInt.of(world.getAsInt())
                : OptionalInt.empty();
        return Optional.of(new RememberedClient(uuid.get(), stringOf(object, NAME), lastWorld, lastSeen.get()));
    }

    private static JsonObject toJson(RememberedClient client) {
        JsonObject object = new JsonObject();
        object.addProperty(UUID, client.accountUuid());
        client.name().ifPresent(name -> object.addProperty(NAME, name));
        client.lastWorld().ifPresent(world -> object.addProperty(LAST_WORLD, world));
        object.addProperty(LAST_SEEN_AT, client.lastSeenAt().toString());
        return object;
    }

    private static Optional<String> stringOf(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return Optional.empty();
        }
        return Optional.of(value.getAsString()).filter(text -> !text.isBlank());
    }

    private static Optional<Instant> instantOf(String text) {
        try {
            return Optional.of(Instant.parse(text));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private void setAside(RuntimeException cause) throws IOException {
        Path aside = file.resolveSibling(file.getFileName() + CORRUPT_SUFFIX);
        Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        log.warn("{} could not be read and was moved to {}; starting with no remembered clients: {}",
                file.getFileName(), aside.getFileName(), cause.toString());
    }
}
