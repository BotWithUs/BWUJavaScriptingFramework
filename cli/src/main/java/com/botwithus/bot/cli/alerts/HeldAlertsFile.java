package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.core.alerts.Alert;
import com.botwithus.bot.core.alerts.AlertKind;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and writes the {@link HeldAlerts} file. The file holds only what an alert
 * already says — its kind, its words and when it happened — which names clients by
 * display name alone.
 */
final class HeldAlertsFile {

    /** What the file held. */
    record Contents(List<Alert> kept, Map<AlertKind, Integer> overflow) {

        static final Contents EMPTY = new Contents(List.of(), Map.of());
    }

    private static final Logger log = LoggerFactory.getLogger(HeldAlertsFile.class);

    private static final int FORMAT_VERSION = 1;
    private static final String VERSION = "version";
    private static final String ALERTS = "alerts";
    private static final String OVERFLOW = "overflow";
    private static final String KIND = "kind";
    private static final String HEADLINE = "headline";
    private static final String DETAIL = "detail";
    private static final String AT = "at";
    private static final String CORRUPT_SUFFIX = ".corrupt";

    private final Path path;

    HeldAlertsFile(Path path) {
        this.path = path;
    }

    /** What the file holds; empty when there is no file, or it cannot be read (it is then moved aside). */
    Contents read() {
        if (!Files.exists(path)) {
            return Contents.EMPTY;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return parse(JsonParser.parseString(json).getAsJsonObject());
        } catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException
                 | NumberFormatException e) {
            moveAside(e);
            return Contents.EMPTY;
        }
    }

    /** Saves {@code kept} and {@code overflow}; with nothing held, removes the file instead. */
    void write(List<Alert> kept, Map<AlertKind, Integer> overflow) throws IOException {
        if (kept.isEmpty() && overflow.isEmpty()) {
            Files.deleteIfExists(path);
            return;
        }
        JsonArray alerts = new JsonArray();
        for (Alert alert : kept) {
            JsonObject object = new JsonObject();
            object.addProperty(KIND, alert.kind().id());
            object.addProperty(HEADLINE, alert.headline());
            object.addProperty(DETAIL, alert.detail());
            object.addProperty(AT, alert.at().toString());
            alerts.add(object);
        }
        JsonObject counts = new JsonObject();
        overflow.forEach((kind, count) -> counts.addProperty(kind.id(), count));
        JsonObject root = new JsonObject();
        root.addProperty(VERSION, FORMAT_VERSION);
        root.add(ALERTS, alerts);
        root.add(OVERFLOW, counts);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        AtomicFiles.write(path, json.getBytes(StandardCharsets.UTF_8));
    }

    private static Contents parse(JsonObject root) {
        List<Alert> kept = new ArrayList<>();
        JsonElement alerts = root.get(ALERTS);
        if (alerts != null) {
            for (JsonElement element : alerts.getAsJsonArray()) {
                alertOf(element.getAsJsonObject()).ifPresent(kept::add);
            }
        }
        Map<AlertKind, Integer> overflow = new EnumMap<>(AlertKind.class);
        JsonElement counts = root.get(OVERFLOW);
        if (counts != null) {
            for (Map.Entry<String, JsonElement> entry : counts.getAsJsonObject().entrySet()) {
                int count = entry.getValue().getAsInt();
                kindOf(entry.getKey()).filter(kind -> count > 0).ifPresent(kind -> overflow.put(kind, count));
            }
        }
        return new Contents(kept, overflow);
    }

    private static Optional<Alert> alertOf(JsonObject object) {
        Optional<AlertKind> kind = stringOf(object, KIND).flatMap(HeldAlertsFile::kindOf);
        Optional<String> headline = stringOf(object, HEADLINE);
        Optional<Instant> at = stringOf(object, AT).flatMap(HeldAlertsFile::instantOf);
        if (kind.isEmpty() || headline.isEmpty() || headline.get().isBlank() || at.isEmpty()) {
            log.warn("Skipping a held alert that could not be read");
            return Optional.empty();
        }
        return Optional.of(new Alert(kind.get(), headline.get(), stringOf(object, DETAIL).orElse(""), at.get()));
    }

    private static Optional<AlertKind> kindOf(String id) {
        for (AlertKind kind : AlertKind.values()) {
            if (kind.id().equals(id)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> stringOf(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return Optional.empty();
        }
        return Optional.of(value.getAsString());
    }

    private static Optional<Instant> instantOf(String text) {
        try {
            return Optional.of(Instant.parse(text));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private void moveAside(Exception cause) {
        Path aside = path.resolveSibling(path.getFileName() + CORRUPT_SUFFIX);
        try {
            Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING);
            log.warn("{} could not be read and was moved to {}; no alerts are held: {}",
                    path.getFileName(), aside.getFileName(), cause.toString());
        } catch (IOException e) {
            log.warn("{} could not be read, nor moved aside; no alerts are held: {}",
                    path.getFileName(), cause.toString());
        }
    }
}
