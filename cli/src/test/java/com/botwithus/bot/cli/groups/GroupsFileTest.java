package com.botwithus.bot.cli.groups;

import com.botwithus.bot.cli.groups.GroupsFile.Contents;
import com.botwithus.bot.cli.groups.GroupsFile.LegacyGroup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reads and writes a real {@code groups.json} in a temporary folder. */
class GroupsFileTest {

    private static final String UUID_A = "0123456789abcdef0123456789abcdef";
    private static final String UUID_B = "fedcba9876543210fedcba9876543210";

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve(GroupsFile.FILE_NAME);
    }

    @Test
    void aGroupRoundTrips_withEveryField() throws IOException {
        ClientGroup group = new ClientGroup(GroupId.random(), "Woodcutters", Optional.of("Yews at Seers'"),
                List.of(UUID_A, UUID_B), List.of("BotWithUs_77"), Optional.of(new ManagerSlot("Breaks", true)));
        ClientGroup bare = ClientGroup.create("Empty", Optional.empty());

        new GroupsFile(file()).write(List.of(group, bare));

        assertEquals(new Contents.Current(List.of(group, bare)), new GroupsFile(file()).read());
    }

    @Test
    void theFileNamesItsVersion_andKeysGroupsById() throws IOException {
        ClientGroup group = ClientGroup.create("Farm", Optional.empty());

        new GroupsFile(file()).write(List.of(group));

        String json = Files.readString(file());
        assertAll(
                () -> assertTrue(json.contains("\"version\": " + GroupsFile.FORMAT_VERSION), json),
                () -> assertTrue(json.contains("\"" + group.id() + "\""), json),
                () -> assertTrue(json.contains("\"manager\": null"), json));
    }

    @Test
    void aNameKeyedFile_isReadAsLegacy_withItsText() throws IOException {
        String text = """
                {
                  "farm": { "description": "Skillers", "members": ["BotWithUs_1", "BotWithUs_2"] },
                  "bosses": { "members": [] }
                }""";
        Files.writeString(file(), text);

        Contents contents = new GroupsFile(file()).read();

        assertEquals(new Contents.Legacy(List.of(
                new LegacyGroup("farm", Optional.of("Skillers"), List.of("BotWithUs_1", "BotWithUs_2")),
                new LegacyGroup("bosses", Optional.empty(), List.of())), text), contents);
    }

    @Test
    void aMemberThatIsNotAnAccount_isDroppedOnReading() throws IOException {
        GroupId id = GroupId.random();
        Files.writeString(file(), """
                {"version": 2, "groups": {"%s": {"name": "g", "members": ["%s", "dev_uuid", ""]}}}"""
                .formatted(id, UUID_A));

        Contents contents = new GroupsFile(file()).read();

        assertEquals(new Contents.Current(List.of(new ClientGroup(id, "g", Optional.empty(), List.of(UUID_A),
                List.of(), Optional.empty()))), contents);
    }

    @Test
    void aFileThatIsNotJson_isSetAside_andReadAsMissing() throws IOException {
        Files.writeString(file(), "{ not json");

        assertEquals(new Contents.Missing(), new GroupsFile(file()).read());

        assertFalse(Files.exists(file()));
        assertEquals("{ not json", Files.readString(dir.resolve(GroupsFile.FILE_NAME + ".corrupt")));
    }

    @Test
    void aFileFromANewerHost_isSetAside_notOverwritten() throws IOException {
        String newer = "{\"version\": 3, \"groups\": {}}";
        Files.writeString(file(), newer);

        assertEquals(new Contents.Missing(), new GroupsFile(file()).read());

        assertEquals(newer, Files.readString(dir.resolve(GroupsFile.FILE_NAME + ".unsupported")));
    }

    @Test
    void aVersionThatIsNotANumber_isSetAside_ratherThanFailingTheLoad() throws IOException {
        Files.writeString(file(), "{\"version\": \"two\", \"groups\": {}}");

        assertEquals(new Contents.Missing(), new GroupsFile(file()).read());

        assertTrue(Files.exists(dir.resolve(GroupsFile.FILE_NAME + ".unsupported")));
    }

    @Test
    void theLegacyBackup_holdsTheTextVerbatim() throws IOException {
        String text = "{\"farm\": {\"members\": [\"BotWithUs_1\"]}}";

        new GroupsFile(file()).backUpLegacy(text);

        assertEquals(text, Files.readString(dir.resolve(GroupsFile.LEGACY_BACKUP_NAME), StandardCharsets.UTF_8));
    }
}
