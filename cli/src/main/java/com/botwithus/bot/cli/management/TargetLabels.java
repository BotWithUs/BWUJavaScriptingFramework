package com.botwithus.bot.cli.management;

import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.cli.management.ManagementSettings.Source;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * What the host calls a management target, and where a target's inherited
 * value comes from, in the words the user sees: "Whole host", a group's name as
 * it is now, "Oakheart · Woodcutting".
 */
public final class TargetLabels {

    /** The label of the whole host. */
    public static final String WHOLE_HOST = "Whole host";
    /** Where a value from the defaults comes from, as in "from defaults". */
    public static final String DEFAULTS = "defaults";
    /** A group target whose group has been deleted. */
    static final String MISSING_GROUP = "Deleted group";
    /** How much of an account UUID stands in for a client that has never shown a name. */
    private static final int SHORT_UUID = 8;
    private static final String SEPARATOR = " · ";

    private final GroupStore groups;
    private final Function<String, Optional<String>> accountNames;

    /**
     * @param accountNames the name a client on an account UUID last showed, if any
     */
    public TargetLabels(GroupStore groups, Function<String, Optional<String>> accountNames) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.accountNames = Objects.requireNonNull(accountNames, "accountNames");
    }

    /** The target's label, e.g. "Woodcutters" or "Oakheart · Woodcutting". */
    public String of(Target target) {
        return switch (target) {
            case Target.Host _ -> WHOLE_HOST;
            case Target.Group group -> groupName(group.id());
            case Target.ClientScript cs -> account(cs.accountUuid()) + SEPARATOR + cs.scriptName();
        };
    }

    /** Where an inherited value comes from: {@value #DEFAULTS}, or the group's name. */
    public String of(Source source) {
        return switch (source) {
            case Source.Defaults _ -> DEFAULTS;
            case Source.FromGroup group -> groupName(group.id());
        };
    }

    private String groupName(GroupId id) {
        return groups.get(id).map(ClientGroup::name).orElse(MISSING_GROUP);
    }

    private String account(String accountUuid) {
        return accountNames.apply(accountUuid)
                .filter(name -> !name.isBlank())
                .orElseGet(() -> accountUuid.substring(0, Math.min(SHORT_UUID, accountUuid.length())));
    }
}
