package com.botwithus.bot.core.sdn;

import com.botwithus.bot.core.config.AtomicFiles;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.time.Instant;
import java.time.InstantSource;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Remembers which scripts this host installed from the Store.
 *
 * <p>A Store delivery never becomes a file the host could look at later, so this
 * record is the only way to tell a Store script from a local build once it is
 * loaded, to offer to reinstall one after a restart, and to know which build was
 * installed. It lives in {@value #FILE_NAME} under the host's settings directory,
 * keyed by the script's class name.
 *
 * <p>Reads are served from memory after the first, so a page may ask every frame.
 * Each {@link #record} reads the file again before writing it, so an install
 * recorded by another host process since this one loaded is kept, and replaces
 * the file atomically.
 */
public final class InstalledScriptsLedger {

    /** The ledger's file name inside the base directory. */
    public static final String FILE_NAME = "sdn-installed.json";

    private static final Logger log = LoggerFactory.getLogger(InstalledScriptsLedger.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int FORMAT_VERSION = 1;
    private static final String KEY_VERSION = "version";
    private static final String KEY_SCRIPTS = "scripts";
    private static final String KEY_CATALOGUE_ID = "catalogueId";
    private static final String KEY_INSTALLED_BUILD = "installedBuild";
    private static final String KEY_INSTALLED_AT = "installedAt";

    private final Path file;
    private final InstantSource clock;
    private final Object lock = new Object();
    /** Guarded by {@link #lock}; {@code null} until the file has been read once. */
    private Map<String, InstalledSdnScript> cached;

    /**
     * @param baseDir the host's settings directory; the ledger is {@value #FILE_NAME} inside it
     * @param clock   stamps each install
     */
    public InstalledScriptsLedger(Path baseDir, InstantSource clock) {
        this.file = baseDir.resolve(FILE_NAME);
        this.clock = clock;
    }

    /** The ledger in the user's {@code ~/.botwithus} directory. */
    public static InstalledScriptsLedger inUserHome() {
        return new InstalledScriptsLedger(
                Path.of(System.getProperty("user.home"), ".botwithus"), InstantSource.system());
    }

    /** Where the ledger is kept. */
    public Path file() {
        return file;
    }

    /** The recorded install of the script with class {@code scriptClass}, if the Store installed it. */
    public Optional<InstalledSdnScript> find(String scriptClass) {
        return Optional.ofNullable(loaded().get(scriptClass));
    }

    /** Every recorded install, keyed by script class. An immutable copy. */
    public Map<String, InstalledSdnScript> all() {
        return loaded();
    }

    /**
     * Records that each script class in {@code installed} was just installed for the
     * catalogue entry it maps to, replacing any earlier record of that class. The
     * build recorded is the entry's {@link SdnCatalogueEntry#currentBuild()}.
     *
     * @throws IOException when the ledger could not be read or written; the
     *                     file on disk is then unchanged
     */
    public void record(Map<String, SdnCatalogueEntry> installed) throws IOException {
        if (installed.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        synchronized (lock) {
            Map<String, InstalledSdnScript> merged = new TreeMap<>(readFile());
            installed.forEach((scriptClass, entry) -> merged.put(scriptClass,
                    new InstalledSdnScript(entry.id(), entry.currentBuild(), now)));
            AtomicFiles.write(file, GSON.toJson(toJson(merged)).getBytes(StandardCharsets.UTF_8));
            cached = Map.copyOf(merged);
        }
    }

    private Map<String, InstalledSdnScript> loaded() {
        synchronized (lock) {
            if (cached == null) {
                try {
                    cached = readFile();
                } catch (IOException e) {
                    // Not cached: a file another host is replacing right now reads fine a moment later.
                    log.debug("SDN: could not read {}: {}", file, e.getMessage());
                    return Map.of();
                }
            }
            return cached;
        }
    }

    /**
     * The file's records. An absent file is an empty ledger, and so is one that is
     * not a ledger at all, which the next {@link #record} replaces. A file that
     * exists but cannot be read throws, so that it is never overwritten blind.
     */
    private Map<String, InstalledSdnScript> readFile() throws IOException {
        if (!Files.exists(file)) {
            return Map.of();
        }
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        try {
            return parse(JsonParser.parseString(text));
        } catch (JsonParseException e) {
            log.warn("SDN: {} is not a readable ledger and will be replaced on the next install: {}",
                    file, e.getMessage());
            return Map.of();
        }
    }

    private static Map<String, InstalledSdnScript> parse(JsonElement root) {
        if (!root.isJsonObject()) {
            return Map.of();
        }
        JsonElement scripts = root.getAsJsonObject().get(KEY_SCRIPTS);
        if (scripts == null || !scripts.isJsonObject()) {
            return Map.of();
        }
        Map<String, InstalledSdnScript> parsed = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : scripts.getAsJsonObject().entrySet()) {
            recordOf(e.getValue()).ifPresent(record -> parsed.put(e.getKey(), record));
        }
        return Map.copyOf(parsed);
    }

    /** One record, or empty when it lacks a catalogue id or a readable install time. */
    private static Optional<InstalledSdnScript> recordOf(JsonElement element) {
        if (!element.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject o = element.getAsJsonObject();
        String catalogueId = stringOf(o, KEY_CATALOGUE_ID);
        Instant installedAt = instantOf(stringOf(o, KEY_INSTALLED_AT));
        if (catalogueId == null || installedAt == null) {
            return Optional.empty();
        }
        return Optional.of(new InstalledSdnScript(catalogueId, buildOf(o), installedAt));
    }

    private static JsonObject toJson(Map<String, InstalledSdnScript> records) {
        JsonObject scripts = new JsonObject();
        records.forEach((scriptClass, record) -> {
            JsonObject o = new JsonObject();
            o.addProperty(KEY_CATALOGUE_ID, record.catalogueId());
            if (record.installedBuild() != null) {
                o.addProperty(KEY_INSTALLED_BUILD, record.installedBuild());
            }
            o.addProperty(KEY_INSTALLED_AT, record.installedAt().toString());
            scripts.add(scriptClass, o);
        });
        JsonObject root = new JsonObject();
        root.addProperty(KEY_VERSION, FORMAT_VERSION);
        root.add(KEY_SCRIPTS, scripts);
        return root;
    }

    private static String stringOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return null;
        }
        return e.getAsString();
    }

    private static Integer buildOf(JsonObject o) {
        JsonElement e = o.get(KEY_INSTALLED_BUILD);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return e.getAsInt();
        } catch (NumberFormatException notAnInt) {
            return null;
        }
    }

    private static Instant instantOf(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
