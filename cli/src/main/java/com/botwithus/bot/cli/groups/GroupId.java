package com.botwithus.bot.cli.groups;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A group's identity. It never changes, so a group can be renamed without
 * breaking anything that refers to it by id.
 *
 * @param value the id; its text form is what {@code groups.json} stores
 */
public record GroupId(UUID value) {

    /** Seeds the ids of groups migrated from the name-keyed file. */
    private static final String MIGRATED_PREFIX = "groups.v1/";

    public GroupId {
        Objects.requireNonNull(value, "value");
    }

    /** A new id, for a new group. */
    public static GroupId random() {
        return new GroupId(UUID.randomUUID());
    }

    /**
     * The id a group named {@code name} in the name-keyed file gets when it is
     * migrated. Always the same for the same name, so migrating the same file
     * twice gives the same ids.
     */
    public static GroupId migratedFrom(String name) {
        byte[] seed = (MIGRATED_PREFIX + name).getBytes(StandardCharsets.UTF_8);
        return new GroupId(UUID.nameUUIDFromBytes(seed));
    }

    /** The id {@code text} spells, if it spells one. */
    public static Optional<GroupId> parse(String text) {
        try {
            return Optional.of(new GroupId(UUID.fromString(text)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
