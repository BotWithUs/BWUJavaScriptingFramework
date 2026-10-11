package com.botwithus.bot.cli.sdnlive;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The catalogue the courier answers with, in the shape the launcher writes it
 * and {@code SdnCatalogueSource} reads it.
 *
 * <p>Every entry is subscribed, free and runs on this host, so both screens list
 * it and offer to install it. Nothing about it may decide the outcome but the
 * class it names.
 */
final class SdnLiveCatalogue {

    /**
     * Not {@code catalogue.json}: that is the host's cached-reply fallback in the
     * rendezvous directory, and a fixture that could be mistaken for it could pass
     * a run the courier never answered.
     */
    private static final String FILE_NAME = "courier-catalogue.json";
    private static final String STATUS_OK = "ok";
    private static final String AUTHOR = "BotWithUs";
    private static final String VERSION = "1.0";
    private static final String API_VERSION = "2";
    private static final int CURRENT_BUILD = 1;

    private SdnLiveCatalogue() {
    }

    /** Writes the catalogue for {@code cases} into {@code dir} and returns its path. */
    static Path write(Path dir, List<SdnLiveCase> cases) throws IOException {
        JsonArray entries = new JsonArray();
        cases.forEach(c -> entries.add(entryOf(c)));
        JsonObject root = new JsonObject();
        root.addProperty("status", STATUS_OK);
        root.add("entries", entries);
        Files.createDirectories(dir);
        Path file = dir.resolve(FILE_NAME);
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static JsonObject entryOf(SdnLiveCase c) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.id());
        o.addProperty("name", c.displayName());
        o.addProperty("author", AUTHOR);
        o.addProperty("subscriber", "");
        o.addProperty("version", VERSION);
        o.addProperty("apiVersion", API_VERSION);
        o.addProperty("tagline", "SDN live check payload");
        o.addProperty("description", "Does nothing in the game.");
        o.addProperty("scriptClass", c.catalogueClass());
        o.addProperty("agentv1Support", true);
        o.addProperty("agentv2Support", true);
        o.addProperty("subscribed", true);
        o.addProperty("isFree", true);
        o.addProperty("currentBuild", CURRENT_BUILD);
        return o;
    }
}
