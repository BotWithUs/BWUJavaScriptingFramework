package com.botwithus.bot.cli.groups;

import com.botwithus.bot.cli.AccountReply;
import com.botwithus.bot.core.config.AtomicFiles;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads and writes {@code groups.json}, in the host's data folder.
 *
 * <p>The file is written in the current format, {@value #FORMAT_VERSION}: groups
 * keyed by {@link GroupId}, members by account UUID. It is still read in the
 * format hosts wrote before that, which kept groups by name and members by pipe
 * name; {@link Contents.Legacy} returns that as it was, for
 * {@link GroupMigration} to convert.</p>
 *
 * <p>Saves go through {@link AtomicFiles}, so a crash mid-save leaves the old
 * file whole. A file that is not valid JSON, or is from a newer host, is set
 * aside beside it, logged, and read as empty: it never stops the host starting,
 * and the next save does not destroy it.</p>
 */
public final class GroupsFile {

    private static final Logger log = LoggerFactory.getLogger(GroupsFile.class);

    /** The file's name in the host's data folder. */
    public static final String FILE_NAME = "groups.json";

    /** Where the name-keyed file is kept once it has been migrated. */
    public static final String LEGACY_BACKUP_NAME = "groups.v1.json";

    /** Bumped when the shape changes in a way an older host cannot read. */
    static final int FORMAT_VERSION = 2;

    private static final String VERSION = "version";
    private static final String GROUPS = "groups";
    private static final String NAME = "name";
    private static final String DESCRIPTION = "description";
    private static final String MEMBERS = "members";
    private static final String UNRESOLVED = "unresolved";
    private static final String MANAGER = "manager";
    private static final String SCRIPT = "script";
    private static final String DESIRED_RUNNING = "desiredRunning";
    private static final String CORRUPT_SUFFIX = ".corrupt";
    private static final String UNSUPPORTED_SUFFIX = ".unsupported";

    /** What the file held. */
    public sealed interface Contents {

        /** No file, or one that was set aside. */
        record Missing() implements Contents { }

        /** Groups in the current format. */
        record Current(List<ClientGroup> groups) implements Contents {
            public Current {
                groups = List.copyOf(groups);
            }
        }

        /**
         * Groups in the name-keyed format.
         *
         * @param text the file as it was, for the backup
         */
        record Legacy(List<LegacyGroup> groups, String text) implements Contents {
            public Legacy {
                groups = List.copyOf(groups);
                Objects.requireNonNull(text, "text");
            }
        }
    }

    /**
     * A group as the name-keyed format kept it.
     *
     * @param pipes the members, by the pipe name they were on when added
     */
    public record LegacyGroup(String name, Optional<String> description, List<String> pipes) {
        public LegacyGroup {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            pipes = List.copyOf(pipes);
        }
    }

    private final Path file;

    /** @param file the file; its folder is created on the first save */
    public GroupsFile(Path file) {
        this.file = file;
    }

    /** Reads the file. */
    public Contents read() throws IOException {
        if (!Files.exists(file)) {
            return new Contents.Missing();
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        try {
            return parse(JsonParser.parseString(text).getAsJsonObject(), text);
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            setAside(CORRUPT_SUFFIX, "could not be read: " + e);
            return new Contents.Missing();
        }
    }

    /** Replaces the file with {@code groups}, in the current format. */
    public void write(List<ClientGroup> groups) throws IOException {
        JsonObject byId = new JsonObject();
        groups.forEach(group -> byId.add(group.id().toString(), toJson(group)));
        JsonObject root = new JsonObject();
        root.addProperty(VERSION, FORMAT_VERSION);
        root.add(GROUPS, byId);
        String json = new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root);
        AtomicFiles.write(file, json.getBytes(StandardCharsets.UTF_8));
    }

    /** Keeps the name-keyed file's {@code text} beside the file, as {@value #LEGACY_BACKUP_NAME}. */
    public void backUpLegacy(String text) throws IOException {
        AtomicFiles.write(file.resolveSibling(LEGACY_BACKUP_NAME), text.getBytes(StandardCharsets.UTF_8));
    }

    private Contents parse(JsonObject root, String text) throws IOException {
        JsonElement version = root.get(VERSION);
        if (version == null || !version.isJsonPrimitive()) {
            return new Contents.Legacy(parseLegacy(root), text);
        }
        if (!version.getAsJsonPrimitive().isNumber() || version.getAsInt() != FORMAT_VERSION) {
            setAside(UNSUPPORTED_SUFFIX, "is version " + version.getAsString() + ", which this host cannot read");
            return new Contents.Missing();
        }
        JsonElement groups = root.get(GROUPS);
        List<ClientGroup> parsed = new ArrayList<>();
        if (groups != null) {
            for (Map.Entry<String, JsonElement> entry : groups.getAsJsonObject().entrySet()) {
                fromJson(entry.getKey(), entry.getValue().getAsJsonObject()).ifPresent(parsed::add);
            }
        }
        return new Contents.Current(parsed);
    }

    private static List<LegacyGroup> parseLegacy(JsonObject root) {
        List<LegacyGroup> groups = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            JsonObject group = entry.getValue().getAsJsonObject();
            groups.add(new LegacyGroup(entry.getKey(), stringOf(group, DESCRIPTION), stringsOf(group, MEMBERS)));
        }
        return groups;
    }

    private static Optional<ClientGroup> fromJson(String idText, JsonObject object) {
        Optional<GroupId> id = GroupId.parse(idText);
        Optional<String> name = stringOf(object, NAME);
        if (id.isEmpty() || name.isEmpty()) {
            log.warn("Skipping a group with no valid id or name: {}", idText);
            return Optional.empty();
        }
        List<String> members = new ArrayList<>();
        for (String member : stringsOf(object, MEMBERS)) {
            AccountReply.identified(member).ifPresentOrElse(members::add,
                    () -> log.warn("Group '{}': dropping member '{}', which is not an account UUID",
                            name.get(), member));
        }
        return Optional.of(new ClientGroup(id.get(), name.get(), stringOf(object, DESCRIPTION), members,
                stringsOf(object, UNRESOLVED), managerOf(object)));
    }

    private static Optional<ManagerSlot> managerOf(JsonObject group) {
        JsonElement manager = group.get(MANAGER);
        if (manager == null || !manager.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject slot = manager.getAsJsonObject();
        JsonElement running = slot.get(DESIRED_RUNNING);
        boolean shouldRun = running != null && running.isJsonPrimitive() && running.getAsBoolean();
        return stringOf(slot, SCRIPT).map(script -> new ManagerSlot(script, shouldRun));
    }

    private static JsonObject toJson(ClientGroup group) {
        JsonObject object = new JsonObject();
        object.addProperty(NAME, group.name());
        group.description().ifPresent(text -> object.addProperty(DESCRIPTION, text));
        object.add(MEMBERS, arrayOf(group.members()));
        if (!group.unresolved().isEmpty()) {
            object.add(UNRESOLVED, arrayOf(group.unresolved()));
        }
        object.add(MANAGER, group.manager().<JsonElement>map(GroupsFile::toJson).orElse(JsonNull.INSTANCE));
        return object;
    }

    private static JsonObject toJson(ManagerSlot manager) {
        JsonObject object = new JsonObject();
        object.addProperty(SCRIPT, manager.script());
        object.addProperty(DESIRED_RUNNING, manager.shouldRun());
        return object;
    }

    private static JsonArray arrayOf(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static Optional<String> stringOf(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return Optional.empty();
        }
        return Optional.of(value.getAsString()).filter(text -> !text.isBlank());
    }

    private static List<String> stringsOf(JsonObject object, String key) {
        JsonElement value = object.get(key);
        List<String> values = new ArrayList<>();
        if (value == null || !value.isJsonArray()) {
            return values;
        }
        for (JsonElement element : value.getAsJsonArray()) {
            if (element.isJsonPrimitive() && !element.getAsString().isBlank()) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    private void setAside(String suffix, String why) throws IOException {
        Path aside = file.resolveSibling(file.getFileName() + suffix);
        Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
        log.warn("{} {} and was moved to {}; starting with no groups", file.getFileName(), why, aside.getFileName());
    }
}
