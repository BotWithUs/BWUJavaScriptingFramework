package com.botwithus.bot.cli.sdn;

import com.botwithus.bot.core.config.AtomicFiles;

import com.google.gson.Gson;
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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * The Script Store's per-user memory: which catalogue scripts are favourites, and
 * which the user has already been shown, so the Store can say how many are new.
 * Both are sets of catalogue ids, kept in {@value #FILE_NAME} under the host's
 * settings directory.
 *
 * <p>Reads are answered from memory, so the render thread may ask every frame. The
 * file is read once, on first use. Every change is saved through the writer given
 * at construction, never on the caller's thread, and each save writes the state as
 * it is when the save runs, so saves that overtake each other still leave the
 * latest state on disk. A save that fails is logged; the choice stays in memory.
 */
public final class FavouritesStore {

    /** The store's file name inside the base directory. */
    public static final String FILE_NAME = "favourites.json";

    private static final Logger log = LoggerFactory.getLogger(FavouritesStore.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int FORMAT_VERSION = 1;
    private static final String KEY_VERSION = "version";
    private static final String KEY_FAVOURITES = "favourites";
    private static final String KEY_SEEN = "seen";

    private final Path file;
    private final Executor writer;
    /** Held while a save writes, so two saves never race on the file. */
    private final Object saveLock = new Object();

    // Guarded by this.
    private final Set<String> favourites = new LinkedHashSet<>();
    private final Set<String> seen = new LinkedHashSet<>();
    private boolean hasSeenBaseline;
    private boolean isLoaded;

    /**
     * @param baseDir the host's settings directory; the store is {@value #FILE_NAME} inside it
     * @param writer  runs each save; the host passes one that is off the render thread
     */
    public FavouritesStore(Path baseDir, Executor writer) {
        this.file = baseDir.resolve(FILE_NAME);
        this.writer = writer;
    }

    /** The store in the user's {@code ~/.botwithus} directory, saving on a virtual thread. */
    public static FavouritesStore inUserHome() {
        return new FavouritesStore(Path.of(System.getProperty("user.home"), ".botwithus"),
                task -> Thread.ofVirtual().name("favourites-save").start(task));
    }

    /** Where the store is kept. */
    public Path file() {
        return file;
    }

    public synchronized boolean isFavourite(String catalogueId) {
        ensureLoaded();
        return favourites.contains(catalogueId);
    }

    /** The favourite catalogue ids, in the order they were starred. An immutable copy. */
    public synchronized Set<String> favourites() {
        ensureLoaded();
        return Collections.unmodifiableSet(new LinkedHashSet<>(favourites));
    }

    public void setFavourite(String catalogueId, boolean favourite) {
        boolean changed;
        synchronized (this) {
            ensureLoaded();
            changed = favourite ? favourites.add(catalogueId) : favourites.remove(catalogueId);
        }
        if (changed) {
            save();
        }
    }

    /** Stars {@code catalogueId} if it is not a favourite, else unstars it; returns the new state. */
    public boolean toggleFavourite(String catalogueId) {
        boolean isNowFavourite;
        synchronized (this) {
            ensureLoaded();
            isNowFavourite = !favourites.remove(catalogueId);
            if (isNowFavourite) {
                favourites.add(catalogueId);
            }
        }
        save();
        return isNowFavourite;
    }

    /**
     * The ids in {@code catalogueIds} the user has not been shown, in the order given.
     *
     * <p>Nothing is new until {@link #markSeen} has been called once: the first
     * catalogue a user ever opens is the baseline, not a list of every script as new.
     */
    public synchronized List<String> unseen(Collection<String> catalogueIds) {
        ensureLoaded();
        if (!hasSeenBaseline) {
            return List.of();
        }
        return catalogueIds.stream().filter(id -> !seen.contains(id)).toList();
    }

    /** Records that the user has been shown {@code catalogueIds}; the first call sets the baseline. */
    public void markSeen(Collection<String> catalogueIds) {
        boolean changed;
        synchronized (this) {
            ensureLoaded();
            changed = seen.addAll(catalogueIds) || !hasSeenBaseline;
            hasSeenBaseline = true;
        }
        if (changed) {
            save();
        }
    }

    private void save() {
        writer.execute(this::writeCurrentState);
    }

    private void writeCurrentState() {
        synchronized (saveLock) {
            byte[] data;
            synchronized (this) {
                ensureLoaded();
                if (!isLoaded) {
                    log.warn("Not saving Script Store favourites: {} could not be read, "
                            + "and saving now would replace it", file);
                    return;
                }
                data = GSON.toJson(toJson()).getBytes(StandardCharsets.UTF_8);
            }
            try {
                AtomicFiles.write(file, data);
            } catch (IOException e) {
                log.warn("Could not save Script Store favourites to {}: {}", file, e.getMessage());
            }
        }
    }

    // Guarded by this.
    private JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty(KEY_VERSION, FORMAT_VERSION);
        root.add(KEY_FAVOURITES, toArray(favourites));
        if (hasSeenBaseline) {
            root.add(KEY_SEEN, toArray(seen));
        }
        return root;
    }

    private static JsonArray toArray(Set<String> ids) {
        JsonArray array = new JsonArray();
        ids.forEach(array::add);
        return array;
    }

    /**
     * Reads the file the first time it is needed. A file that is absent or not one of
     * ours counts as read (and empty); one that exists but cannot be read right now
     * does not, so it is tried again, and nothing is saved over it meanwhile.
     * Guarded by this.
     */
    private void ensureLoaded() {
        if (isLoaded) {
            return;
        }
        try {
            readRoot().ifPresent(root -> {
                idsOf(root.get(KEY_FAVOURITES)).ifPresent(favourites::addAll);
                idsOf(root.get(KEY_SEEN)).ifPresent(ids -> {
                    seen.addAll(ids);
                    hasSeenBaseline = true;
                });
            });
            isLoaded = true;
        } catch (IOException e) {
            log.debug("Could not read Script Store favourites from {}: {}", file, e.getMessage());
        }
    }

    /** The file's top-level object; empty when there is no file or it is not one of ours. */
    private Optional<JsonObject> readRoot() throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        try {
            JsonElement root = JsonParser.parseString(text);
            return root.isJsonObject() ? Optional.of(root.getAsJsonObject()) : Optional.empty();
        } catch (JsonParseException e) {
            log.warn("Script Store favourites in {} are unreadable and will be replaced: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** The string ids in a JSON array, skipping anything else; empty when it is not an array. */
    private static Optional<List<String>> idsOf(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return Optional.empty();
        }
        List<String> ids = element.getAsJsonArray().asList().stream()
                .filter(e -> e.isJsonPrimitive() && e.getAsJsonPrimitive().isString())
                .map(JsonElement::getAsString)
                .toList();
        return Optional.of(ids);
    }
}
