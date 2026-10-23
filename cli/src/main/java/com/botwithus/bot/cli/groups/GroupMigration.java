package com.botwithus.bot.cli.groups;

import com.botwithus.bot.cli.groups.GroupsFile.LegacyGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Converts groups saved by name with pipe-name members into groups by id with
 * account-UUID members.
 *
 * <p>A pipe name identifies a client only while its game runs, so each member
 * is looked up among the clients connected now. One whose account is known
 * becomes that account; one whose account is not is kept on the group as
 * {@linkplain ClientGroup#unresolved() unresolved}, never dropped, so the user
 * can see it and remove it, and the host can still resolve it if a client on
 * that pipe is identified later.</p>
 *
 * <p>Deterministic: the same groups and the same connected clients always give
 * the same result, ids included, so a migration interrupted part-way can simply
 * run again.</p>
 */
public final class GroupMigration {

    private GroupMigration() {
    }

    /**
     * @param legacy        the groups as the old file held them, in file order
     * @param accountOnPipe the account UUID of the client identified on a pipe
     *                      now, for a client that can be a group member
     */
    public static List<ClientGroup> migrate(List<LegacyGroup> legacy,
                                            Function<String, Optional<String>> accountOnPipe) {
        List<ClientGroup> groups = new ArrayList<>();
        for (LegacyGroup group : legacy) {
            groups.add(migrate(group, accountOnPipe));
        }
        return groups;
    }

    private static ClientGroup migrate(LegacyGroup group, Function<String, Optional<String>> accountOnPipe) {
        List<String> members = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (String pipe : group.pipes()) {
            accountOnPipe.apply(pipe).ifPresentOrElse(members::add, () -> unresolved.add(pipe));
        }
        return new ClientGroup(GroupId.migratedFrom(group.name()), group.name(), group.description(),
                members, unresolved, Optional.empty());
    }
}
