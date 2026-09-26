package com.botwithus.bot.core.sdn;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Asks the launcher for the signed-in account's script catalogue.
 *
 * <p>The host holds no credential and makes no network call of its own. It
 * writes a request into the shared rendezvous directory, the launcher answers
 * with the catalogue it fetched, and this class reads that answer. When the
 * launcher does not answer — most often because it is not running — the last
 * answer it left behind is returned instead, marked stale, so the user sees the
 * scripts they had rather than an empty panel.
 */
public final class SdnCatalogueSource {

    private static final Logger log = LoggerFactory.getLogger(SdnCatalogueSource.class);

    private static final String REQUEST_SUFFIX = ".catreq";
    private static final String REPLY_SUFFIX = ".cat";
    private static final String CACHED_REPLY = "catalogue.json";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final Path directory;

    public SdnCatalogueSource() {
        this(SdnRendezvous.directory());
    }

    public SdnCatalogueSource(Path directory) {
        this.directory = directory;
    }

    public SdnCatalogueResult fetch() {
        return fetch(DEFAULT_TIMEOUT);
    }

    /** Asks the launcher and waits up to {@code timeout} for its answer. */
    public SdnCatalogueResult fetch(Duration timeout) {
        long pid = SdnRendezvous.currentPid();
        Path request = directory.resolve(pid + REQUEST_SUFFIX);
        Path reply = directory.resolve(pid + REPLY_SUFFIX);
        try {
            Files.createDirectories(directory);
            SdnRendezvous.atomicWrite(request, requestBody(pid));
            byte[] answer = SdnRendezvous.awaitFile(reply, timeout);
            if (answer == null) {
                log.info("SDN: launcher did not answer within {} — falling back to cache", timeout);
                return cachedOrUnavailable();
            }
            return parseReply(new String(answer, StandardCharsets.UTF_8), false);
        } catch (IOException e) {
            return new SdnCatalogueResult.Failed(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SdnCatalogueResult.Failed("Interrupted while waiting for the launcher.");
        } finally {
            SdnRendezvous.deleteQuietly(request);
            SdnRendezvous.deleteQuietly(reply);
        }
    }

    private static byte[] requestBody(long pid) {
        JsonObject body = new JsonObject();
        body.addProperty("pid", pid);
        body.addProperty("requestedAtEpochMs", System.currentTimeMillis());
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private SdnCatalogueResult cachedOrUnavailable() {
        Path cached = directory.resolve(CACHED_REPLY);
        if (!Files.isRegularFile(cached)) {
            return new SdnCatalogueResult.CourierUnavailable();
        }
        try {
            String text = Files.readString(cached, StandardCharsets.UTF_8);
            return parseReply(text, true);
        } catch (IOException e) {
            log.debug("SDN: cached catalogue unreadable: {}", e.getMessage());
            return new SdnCatalogueResult.CourierUnavailable();
        }
    }

    /**
     * Reads the launcher's answer. The status field carries why a fetch failed,
     * because only the launcher can tell the difference between nobody being
     * signed in and the account lacking a subscription.
     */
    private static SdnCatalogueResult parseReply(String text, boolean stale) {
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                return new SdnCatalogueResult.Failed("The launcher sent a reply we could not read.");
            }
            JsonObject obj = root.getAsJsonObject();
            String status = stringOf(obj, "status");
            return switch (status) {
                case "ok" -> new SdnCatalogueResult.Delivered(entriesOf(obj), stale);
                case "not_signed_in" -> new SdnCatalogueResult.NotSignedIn();
                case "subscription_required" -> new SdnCatalogueResult.SubscriptionRequired();
                default -> new SdnCatalogueResult.Failed(messageOf(obj));
            };
        } catch (JsonParseException e) {
            return new SdnCatalogueResult.Failed("The launcher sent a reply we could not read.");
        }
    }

    private static String messageOf(JsonObject obj) {
        String message = stringOf(obj, "message");
        return message.isBlank() ? "The launcher could not fetch your scripts." : message;
    }

    private static List<SdnCatalogueEntry> entriesOf(JsonObject obj) {
        JsonElement entries = obj.get("entries");
        if (entries == null || !entries.isJsonArray()) {
            return List.of();
        }
        JsonArray array = entries.getAsJsonArray();
        List<SdnCatalogueEntry> parsed = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                parsed.add(entryOf(element.getAsJsonObject()));
            }
        }
        return parsed;
    }

    private static SdnCatalogueEntry entryOf(JsonObject o) {
        return new SdnCatalogueEntry(
                stringOf(o, "id"),
                stringOf(o, "name"),
                stringOf(o, "author"),
                stringOf(o, "subscriber"),
                stringOf(o, "version"),
                stringOf(o, "apiVersion"),
                stringOf(o, "tagline"),
                stringOf(o, "description"),
                stringOf(o, "scriptClass"),
                boolOf(o, "agentv1Support"),
                boolOf(o, "agentv2Support"),
                optionalBoolOf(o, "subscribed"),
                optionalBoolOf(o, "isFree"));
    }

    private static String stringOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return "";
        }
        return e.getAsString();
    }

    private static boolean boolOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()
                && e.getAsBoolean();
    }

    /**
     * A JSON boolean as {@code TRUE} / {@code FALSE}, and {@code null} for anything
     * else: absent, JSON null, or not a boolean. Unlike {@link #boolOf} this never
     * turns silence into {@code false}, so a launcher that predates the key stays
     * tellable from one that answered no.
     */
    private static Boolean optionalBoolOf(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return e.getAsBoolean();
    }
}
