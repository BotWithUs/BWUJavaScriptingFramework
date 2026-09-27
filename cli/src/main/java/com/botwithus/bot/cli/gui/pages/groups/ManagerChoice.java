package com.botwithus.bot.cli.gui.pages.groups;

import java.util.List;
import java.util.Objects;

/**
 * A management script the Assign manager dialog offers.
 *
 * @param otherGroups the groups it manages already, by name; a group has one manager, so
 *                    assigning it here leaves those as they are
 */
public record ManagerChoice(String script, String version, String description, List<String> otherGroups) {

    public ManagerChoice {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(description, "description");
        otherGroups = List.copyOf(otherGroups);
    }
}
