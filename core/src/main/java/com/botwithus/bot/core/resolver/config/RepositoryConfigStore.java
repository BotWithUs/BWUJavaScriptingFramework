package com.botwithus.bot.core.resolver.config;

import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.driver.MavenRepositoryDriver;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * On-disk store for {@link Repository} entries at
 * {@code ~/.botwithus/repositories.json}. Each saved entry round-trips
 * through Gson with a custom type adapter so {@code Optional<String>}
 * fields don't trip JPMS reflection.
 *
 * <p>The bundled {@code central} repository is added on first load if
 * the file is absent — keeps the user out of "no repos configured" by
 * default. Users can {@code scripts repo remove central} to drop it.</p>
 */
public final class RepositoryConfigStore {

    private static final String TMP_SUFFIX = ".tmp";

    public static final Path DEFAULT_PATH = Path.of(System.getProperty("user.home"), ".botwithus", "repositories.json");
    public static final String BUNDLED_CENTRAL_ID = "central";
    public static final URI BUNDLED_CENTRAL_URL = URI.create("https://repo1.maven.org/maven2/");
    public static final URI BUNDLED_CENTRAL_SEARCH = URI.create("https://search.maven.org/solrsearch/select");

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Repository.class, new RepositoryTypeAdapter())
            .create();
    private static final Type LIST_TYPE = new TypeToken<List<Repository>>() {}.getType();

    private final Path file;
    private final Map<String, Repository> byId = new LinkedHashMap<>();

    public RepositoryConfigStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public synchronized void load() throws IOException {
        byId.clear();
        if (!Files.exists(file)) {
            seedDefaults();
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            List<Repository> loaded = GSON.fromJson(reader, LIST_TYPE);
            if (loaded != null) {
                for (Repository r : loaded) {
                    if (r != null) {
                        byId.put(r.id(), r);
                    }
                }
            }
        } catch (JsonIOException | JsonSyntaxException e) {
            throw new IOException("malformed " + file + ": " + e.getMessage(), e);
        }
    }

    public synchronized void save() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = (parent != null ? parent : Path.of(".")).resolve(file.getFileName() + TMP_SUFFIX);
        try (Writer writer = Files.newBufferedWriter(tmp)) {
            GSON.toJson(List.copyOf(byId.values()), LIST_TYPE, writer);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException atomicFailed) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized List<Repository> all() {
        return List.copyOf(byId.values());
    }

    public synchronized Optional<Repository> find(String id) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(id, "id")));
    }

    public synchronized void put(Repository repository) {
        Objects.requireNonNull(repository, "repository");
        byId.put(repository.id(), repository);
    }

    public synchronized boolean remove(String id) {
        return byId.remove(Objects.requireNonNull(id, "id")) != null;
    }

    private void seedDefaults() {
        byId.put(BUNDLED_CENTRAL_ID, new Repository(
                BUNDLED_CENTRAL_ID,
                BUNDLED_CENTRAL_URL,
                MavenRepositoryDriver.TYPE_ID,
                /* snapshots */ false,
                /* requireSignature */ false,
                Optional.empty(),
                Optional.of(BUNDLED_CENTRAL_SEARCH)));
    }

    /**
     * JSON shape for one repository entry. The custom type adapter
     * sidesteps Gson's reflection-into-Optional path which fails under
     * JPMS on Java 21+.
     */
    private static final class RepositoryTypeAdapter extends TypeAdapter<Repository> {
        @Override
        public void write(JsonWriter out, Repository value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            out.beginObject();
            out.name("id").value(value.id());
            out.name("url").value(value.url().toString());
            out.name("driverId").value(value.driverId());
            out.name("snapshots").value(value.snapshots());
            out.name("requireSignature").value(value.requireSignature());
            if (value.credentialsRef().isPresent()) {
                out.name("credentialsRef").value(value.credentialsRef().get());
            }
            if (value.searchEndpoint().isPresent()) {
                out.name("searchEndpoint").value(value.searchEndpoint().get().toString());
            }
            out.endObject();
        }

        @Override
        public Repository read(JsonReader in) throws IOException {
            String id = null;
            URI url = null;
            String driverId = MavenRepositoryDriver.TYPE_ID;
            boolean snapshots = false;
            boolean requireSignature = false;
            Optional<String> credentialsRef = Optional.empty();
            Optional<URI> searchEndpoint = Optional.empty();
            in.beginObject();
            while (in.hasNext()) {
                String name = in.nextName();
                switch (name) {
                    case "id" -> id = in.nextString();
                    case "url" -> url = URI.create(in.nextString());
                    case "driverId" -> driverId = in.nextString();
                    case "snapshots" -> snapshots = in.nextBoolean();
                    case "requireSignature" -> requireSignature = in.nextBoolean();
                    case "credentialsRef" -> credentialsRef = Optional.of(in.nextString());
                    case "searchEndpoint" -> searchEndpoint = Optional.of(URI.create(in.nextString()));
                    // Backwards-compat: pre-12.1b files used `type: RELEASE|SNAPSHOT` instead of
                    // `driverId` + `snapshots`. Translate on read so existing user files keep working.
                    case "type" -> {
                        String legacy = in.nextString();
                        driverId = MavenRepositoryDriver.TYPE_ID;
                        snapshots = "SNAPSHOT".equalsIgnoreCase(legacy);
                    }
                    default -> in.skipValue();
                }
            }
            in.endObject();
            if (id == null || url == null) {
                throw new IOException("repository entry missing id or url");
            }
            return new Repository(id, url, driverId, snapshots, requireSignature, credentialsRef, searchEndpoint);
        }
    }
}
