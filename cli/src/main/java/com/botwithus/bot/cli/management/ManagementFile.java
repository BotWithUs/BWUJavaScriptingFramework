package com.botwithus.bot.cli.management;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads and writes {@code management.json}, in the host's data folder: for each
 * management script, whether it manages the whole host, which client scripts it
 * manages, and whether it should be running.
 *
 * <p>Group targets are not kept here. A group target is the group's manager
 * slot, which {@code groups.json} keeps, so there is one record of it and the
 * two files cannot disagree.</p>
 *
 * <p>Saves go through {@link AtomicFiles}. A file that is not valid JSON, or is
 * from a newer host, is set aside beside it and read as {@link Contents.Missing},
 * so the host starts as it would on its first run.</p>
 */
public final class ManagementFile {

    private static final Logger log = LoggerFactory.getLogger(ManagementFile.class);

    /** The file's name in the host's data folder. */
    public static final String FILE_NAME = "management.json";

    /** Bumped when the shape changes in a way an older host cannot read. */
    static final int FORMAT_VERSION = 1;

    private static final String VERSION = "version";
    private static final String SCRIPTS = "scripts";
    private static final String WHOLE_HOST = "wholeHost";
    private static final String CLIENT_SCRIPTS = "clientScripts";
    private static final String ACCOUNT = "account";
    private static final String SCRIPT = "script";
    private static final String DESIRED_RUNNING = "desiredRunning";
    private static final String MIGRATION_PENDING = "migrationPending";
    private static final String CORRUPT_SUFFIX = ".corrupt";
    private static final String UNSUPPORTED_SUFFIX = ".unsupported";

    /**
     * What the file keeps for one management script.
     *
     * @param isWholeHost    whether the script manages every client
     * @param clientScripts  the single client scripts it manages
     * @param desiredRunning whether the host should keep it running
     */
    public record Stored(boolean isWholeHost, List<Target.ClientScript> clientScripts, boolean desiredRunning) {

        /** A script with no targets that should not be running. */
        public static final Stored NONE = new Stored(false, List.of(), false);

        public Stored {
            clientScripts = List.copyOf(clientScripts);
        }

        /** Whether this says nothing, so the script need not be written at all. */
        boolean isEmpty() {
            return !isWholeHost && clientScripts.isEmpty() && !desiredRunning;
        }
    }

    /** What the file held. */
    public sealed interface Contents {

        /** No file, or one that was set aside: the host has not kept management targets before. */
        record Missing() implements Contents { }

        /**
         * Each management script the file names, by name.
         *
         * @param isMigrationPending whether the next management scripts found
         *                           are still to be given the whole host; see
         *                           {@link ManagementTargets#recordLoaded}
         */
        record Current(Map<String, Stored> scripts, boolean isMigrationPending) implements Contents {
            public Current {
                scripts = Map.copyOf(scripts);
            }
        }
    }

    private final Path file;

    /** @param file the file; its folder is created on the first save */
    public ManagementFile(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Reads the file. */
    public Contents read() throws IOException {
        if (!Files.exists(file)) {
            return new Contents.Missing();
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        try {
            return parse(JsonParser.parseString(text).getAsJsonObject());
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            setAside(CORRUPT_SUFFIX, "could not be read: " + e);
            return new Contents.Missing();
        }
    }

    /**
     * Replaces the file with {@code scripts}; a script with nothing to keep is left out.
     *
     * @param isMigrationPending see {@link Contents.Current#isMigrationPending()}
     */
    public void write(Map<String, Stored> scripts, boolean isMigrationPending) throws IOException {
        JsonObject byName = new JsonObject();
        scripts.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> byName.add(entry.getKey(), toJson(entry.getValue())));
        JsonObject root = new JsonObject();
        root.addProperty(VERSION, FORMAT_VERSION);
        if (isMigrationPending) {
            root.addProperty(MIGRATION_PENDING, true);
        }
        root.add(SCRIPTS, byName);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        AtomicFiles.write(file, json.getBytes(StandardCharsets.UTF_8));
    }

    private Contents parse(JsonObject root) throws IOException {
        JsonElement version = root.get(VERSION);
        boolean isSupported = version != null && version.isJsonPrimitive()
                && version.getAsJsonPrimitive().isNumber() && version.getAsInt() == FORMAT_VERSION;
        if (!isSupported) {
            setAside(UNSUPPORTED_SUFFIX, "is version " + version + ", which this host cannot read");
            return new Contents.Missing();
        }
        Map<String, Stored> scripts = new LinkedHashMap<>();
        JsonElement byName = root.get(SCRIPTS);
        if (byName != null && byName.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : byName.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonObject() && !entry.getKey().isBlank()) {
                    scripts.put(entry.getKey(), fromJson(entry.getValue().getAsJsonObject()));
                }
            }
        }
        return new Contents.Current(scripts, isTrue(root, MIGRATION_PENDING));
    }

    private static Stored fromJson(JsonObject object) {
        List<Target.ClientScript> clientScripts = new ArrayList<>();
        JsonElement array = object.get(CLIENT_SCRIPTS);
        if (array != null && array.isJsonArray()) {
            for (JsonElement element : array.getAsJsonArray()) {
                clientScriptOf(element).ifPresent(clientScripts::add);
            }
        }
        return new Stored(isTrue(object, WHOLE_HOST), clientScripts, isTrue(object, DESIRED_RUNNING));
    }

    private static Optional<Target.ClientScript> clientScriptOf(JsonElement element) {
        if (!element.isJsonObject()) {
            return Optional.empty();
        }
        Optional<String> account = stringOf(element.getAsJsonObject(), ACCOUNT);
        Optional<String> script = stringOf(element.getAsJsonObject(), SCRIPT);
        if (account.isEmpty() || script.isEmpty()) {
            log.warn("Skipping a client script target with no account or script: {}", element);
            return Optional.empty();
        }
        return Optional.of(new Target.ClientScript(account.get(), script.get()));
    }

    private static JsonObject toJson(Stored stored) {
        JsonObject object = new JsonObject();
        object.addProperty(WHOLE_HOST, stored.isWholeHost());
        JsonArray clientScripts = new JsonArray();
        for (Target.ClientScript target : stored.clientScripts()) {
            JsonObject entry = new JsonObject();
            entry.addProperty(ACCOUNT, target.accountUuid());
            entry.addProperty(SCRIPT, target.scriptName());
            clientScripts.add(entry);
        }
        object.add(CLIENT_SCRIPTS, clientScripts);
        object.addProperty(DESIRED_RUNNING, stored.desiredRunning());
        return object;
    }

    private static boolean isTrue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }

    private static Optional<String> stringOf(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return Optional.empty();
        }
        return Optional.of(value.getAsString()).filter(text -> !text.isBlank());
    }

    private void setAside(String suffix, String why) throws IOException {
        Path aside = file.resolveSibling(file.getFileName() + suffix);
        Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        log.warn("{} {} and was moved to {}; every management script found next is given the whole host",
                file.getFileName(), why, aside.getFileName());
    }
}
