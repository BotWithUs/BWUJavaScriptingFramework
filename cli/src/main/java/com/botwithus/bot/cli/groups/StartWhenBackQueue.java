package com.botwithus.bot.cli.groups;

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
import java.util.function.Predicate;

/**
 * Scripts waiting to start on clients that are not connected: the "start when
 * back" queue. Kept in {@code start-when-back.json} in the host's data folder,
 * so a request survives a host restart as well as the client's.
 *
 * <p>An entry is removed when the client comes back and the script is started
 * on it, and when the user stops that script on that client, which cancels the
 * start rather than letting it happen later. There is at most one entry per
 * account and script.</p>
 *
 * <p>Thread-safe. Reads never block and return immutable snapshots in the order
 * the requests were made. Every change is saved before the call returns.</p>
 */
public final class StartWhenBackQueue {

    private static final Logger log = LoggerFactory.getLogger(StartWhenBackQueue.class);

    /** The file's name in the host's data folder. */
    public static final String FILE_NAME = "start-when-back.json";

    private static final int FORMAT_VERSION = 1;
    private static final String VERSION = "version";
    private static final String QUEUED = "queued";
    private static final String UUID = "uuid";
    private static final String SCRIPT = "script";
    private static final String REQUESTED_AT = "requestedAt";
    private static final String CORRUPT_SUFFIX = ".corrupt";

    private final Path file;
    private final Object lock = new Object();
    /** Replaced whole under {@link #lock}; read without it. */
    private volatile List<QueuedStart> entries = List.of();

    /** @param file the file to keep the queue in; its folder is created on the first save */
    public StartWhenBackQueue(Path file) {
        this.file = file;
    }

    /** Replaces the queue with the one saved in the file. A file that cannot be read leaves it empty. */
    public void load() {
        synchronized (lock) {
            try {
                entries = read();
            } catch (IOException e) {
                log.error("Failed to load the start-when-back queue", e);
            }
        }
    }

    /**
     * Queues {@code script} to start on account {@code accountUuid} when it is back.
     *
     * @return {@code false} if that start was already queued; it keeps its
     *         original request time
     * @throws IllegalArgumentException if {@code accountUuid} is not an account
     *                                  UUID or {@code script} is blank
     */
    public boolean enqueue(String accountUuid, String script, Instant requestedAt) {
        QueuedStart entry = new QueuedStart(accountUuid, script, requestedAt);
        synchronized (lock) {
            if (entries.stream().anyMatch(queued -> queued.isFor(accountUuid, script))) {
                return false;
            }
            List<QueuedStart> next = new ArrayList<>(entries);
            next.add(entry);
            commit(next);
            return true;
        }
    }

    /** Removes the queued start of {@code script} on {@code accountUuid}; {@code false} if there was none. */
    public boolean dequeue(String accountUuid, String script) {
        return removeIf(entry -> entry.isFor(accountUuid, script)) > 0;
    }

    /** Removes every start queued on {@code accountUuid}; returns how many. */
    public int dequeueAll(String accountUuid) {
        return removeIf(entry -> entry.accountUuid().equals(accountUuid));
    }

    /** Removes every queued start of {@code script}, on any account; returns how many. */
    public int dequeueScript(String script) {
        return removeIf(entry -> entry.script().equalsIgnoreCase(script));
    }

    /** Empties the queue; returns how many starts it held. */
    public int clear() {
        return removeIf(entry -> true);
    }

    /** The starts queued on {@code accountUuid}, oldest first. */
    public List<QueuedStart> forAccount(String accountUuid) {
        return entries.stream().filter(entry -> entry.accountUuid().equals(accountUuid)).toList();
    }

    /** Every queued start, oldest first. */
    public List<QueuedStart> all() {
        return entries;
    }

    private int removeIf(Predicate<QueuedStart> match) {
        synchronized (lock) {
            List<QueuedStart> next = entries.stream().filter(match.negate()).toList();
            int removed = entries.size() - next.size();
            if (removed > 0) {
                commit(next);
            }
            return removed;
        }
    }

    /** Publishes {@code next} and saves it. Call with {@link #lock} held. */
    private void commit(List<QueuedStart> next) {
        entries = List.copyOf(next);
        try {
            write(entries);
        } catch (IOException e) {
            log.error("Failed to save the start-when-back queue", e);
        }
    }

    private List<QueuedStart> read() throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        try {
            return parse(JsonParser.parseString(json).getAsJsonObject());
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            Path aside = file.resolveSibling(file.getFileName() + CORRUPT_SUFFIX);
            Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            log.warn("{} could not be read and was moved to {}; starting with an empty queue: {}",
                    file.getFileName(), aside.getFileName(), e.toString());
            return List.of();
        }
    }

    private static List<QueuedStart> parse(JsonObject root) {
        List<QueuedStart> parsed = new ArrayList<>();
        JsonElement queued = root.get(QUEUED);
        if (queued == null) {
            return parsed;
        }
        for (JsonElement element : queued.getAsJsonArray()) {
            fromJson(element.getAsJsonObject()).ifPresent(parsed::add);
        }
        return parsed;
    }

    private static Optional<QueuedStart> fromJson(JsonObject object) {
        Optional<String> uuid = stringOf(object, UUID);
        Optional<String> script = stringOf(object, SCRIPT);
        Optional<Instant> requestedAt = stringOf(object, REQUESTED_AT).flatMap(StartWhenBackQueue::instantOf);
        if (uuid.isEmpty() || script.isEmpty() || requestedAt.isEmpty()) {
            log.warn("Skipping an unreadable start-when-back entry: {}", object);
            return Optional.empty();
        }
        try {
            return Optional.of(new QueuedStart(uuid.get(), script.get(), requestedAt.get()));
        } catch (IllegalArgumentException e) {
            log.warn("Skipping a start-when-back entry that names no account: {}", object);
            return Optional.empty();
        }
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

    private void write(List<QueuedStart> queued) throws IOException {
        JsonArray array = new JsonArray();
        for (QueuedStart entry : queued) {
            JsonObject object = new JsonObject();
            object.addProperty(UUID, entry.accountUuid());
            object.addProperty(SCRIPT, entry.script());
            object.addProperty(REQUESTED_AT, entry.requestedAt().toString());
            array.add(object);
        }
        JsonObject root = new JsonObject();
        root.addProperty(VERSION, FORMAT_VERSION);
        root.add(QUEUED, array);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        AtomicFiles.write(file, json.getBytes(StandardCharsets.UTF_8));
    }
}
