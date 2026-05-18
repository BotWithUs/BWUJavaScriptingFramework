package com.botwithus.bot.core.resolver.install;

import com.botwithus.bot.core.resolver.MavenCoord;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * On-disk index of installed script artifacts. Default location is
 * {@code ~/.botwithus/.installed.json}, but the path is constructor-
 * injected so tests can point it at a tmp dir.
 *
 * <p>Writes are atomic: the JSON is rendered to a sibling {@code .tmp}
 * file then {@link Files#move(Path, Path, java.nio.file.CopyOption...)}
 * with {@link StandardCopyOption#ATOMIC_MOVE} replaces the live file.</p>
 *
 * <p>Not thread-safe. The expected pattern is single-process use from
 * {@code ScriptInstaller}; concurrent CLI invocations would race regardless
 * of how we synchronize within one JVM.</p>
 */
public final class InstalledIndex {

    private static final Logger log = LoggerFactory.getLogger(InstalledIndex.class);
    private static final String TMP_SUFFIX = ".tmp";

    public static final Path DEFAULT_PATH = Path.of(System.getProperty("user.home"), ".botwithus", ".installed.json");

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
            .registerTypeAdapter(MavenCoord.class, new MavenCoordTypeAdapter())
            .create();
    private static final Type LIST_TYPE = new TypeToken<List<InstalledEntry>>() {}.getType();

    private final Path indexFile;
    private final Map<String, InstalledEntry> entries = new LinkedHashMap<>();

    public InstalledIndex(Path indexFile) {
        this.indexFile = Objects.requireNonNull(indexFile, "indexFile");
    }

    public Path indexFile() {
        return indexFile;
    }

    public synchronized void load() throws IOException {
        entries.clear();
        if (!Files.exists(indexFile)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(indexFile)) {
            List<InstalledEntry> loaded = GSON.fromJson(reader, LIST_TYPE);
            if (loaded == null) {
                return;
            }
            for (InstalledEntry e : loaded) {
                if (e != null) {
                    entries.put(e.key(), e);
                }
            }
        } catch (JsonIOException | JsonSyntaxException e) {
            throw new IOException("malformed " + indexFile + ": " + e.getMessage(), e);
        }
    }

    public synchronized void save() throws IOException {
        Path parent = indexFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = (parent != null ? parent : Path.of(".")).resolve(indexFile.getFileName() + TMP_SUFFIX);
        try (Writer writer = Files.newBufferedWriter(tmp)) {
            GSON.toJson(List.copyOf(entries.values()), LIST_TYPE, writer);
        }
        try {
            Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            log.debug("ATOMIC_MOVE not supported on {}; falling back to REPLACE_EXISTING", indexFile.getFileSystem());
            Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized Optional<InstalledEntry> find(MavenCoord coord) {
        Objects.requireNonNull(coord, "coord");
        return Optional.ofNullable(entries.get(coord.ga()));
    }

    public synchronized List<InstalledEntry> all() {
        return List.copyOf(entries.values());
    }

    public synchronized void put(InstalledEntry entry) {
        Objects.requireNonNull(entry, "entry");
        entries.put(entry.key(), entry);
    }

    public synchronized Optional<InstalledEntry> remove(MavenCoord coord) {
        Objects.requireNonNull(coord, "coord");
        return Optional.ofNullable(entries.remove(coord.ga()));
    }

    public synchronized int size() {
        return entries.size();
    }

    private static final class InstantTypeAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            String text = in.nextString();
            return Instant.parse(text);
        }
    }

    private static final class MavenCoordTypeAdapter extends TypeAdapter<MavenCoord> {
        @Override
        public void write(JsonWriter out, MavenCoord value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }
            out.beginObject();
            out.name("groupId").value(value.groupId());
            out.name("artifactId").value(value.artifactId());
            if (value.version().isPresent()) {
                out.name("version").value(value.version().get());
            }
            out.endObject();
        }

        @Override
        public MavenCoord read(JsonReader in) throws IOException {
            String groupId = null;
            String artifactId = null;
            String version = null;
            in.beginObject();
            while (in.hasNext()) {
                String name = in.nextName();
                switch (name) {
                    case "groupId" -> groupId = in.nextString();
                    case "artifactId" -> artifactId = in.nextString();
                    case "version" -> version = in.nextString();
                    default -> in.skipValue();
                }
            }
            in.endObject();
            if (groupId == null || artifactId == null) {
                throw new IOException("MavenCoord missing groupId or artifactId");
            }
            return version == null
                    ? MavenCoord.of(groupId, artifactId)
                    : MavenCoord.of(groupId, artifactId, version);
        }
    }
}
