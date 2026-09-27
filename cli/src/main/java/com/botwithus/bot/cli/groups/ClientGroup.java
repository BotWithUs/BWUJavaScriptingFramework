package com.botwithus.bot.cli.groups;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A named set of clients, one per account UUID. Immutable: every change makes
 * a new group, and {@link GroupStore} holds the current one.
 *
 * <p>Members are account UUIDs, not pipes, so a member stays in the group
 * while its game is closed and after it restarts on a new pipe. A client can
 * be in any number of groups.</p>
 *
 * @param id          never changes; see {@link GroupId}
 * @param name        shown to the user, and how the orchestrator API names the
 *                    group; unique across groups
 * @param description what the group is for, if anyone said
 * @param members     account UUIDs, in the order they joined, no repeats
 * @param unresolved  pipe names carried over from a group saved before members
 *                    were accounts, whose account was not known when it was
 *                    migrated; each is resolved when a client on that pipe is
 *                    identified, or removed by the user
 * @param manager     the management script assigned to the group, if any
 */
public record ClientGroup(GroupId id, String name, Optional<String> description,
                          List<String> members, List<String> unresolved,
                          Optional<ManagerSlot> manager) {

    public ClientGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(manager, "manager");
        if (name.isBlank()) {
            throw new IllegalArgumentException("group name is blank");
        }
        description = description.filter(text -> !text.isBlank());
        members = distinct(members);
        unresolved = distinct(unresolved);
    }

    /** A new, empty group. */
    public static ClientGroup create(String name, Optional<String> description) {
        return new ClientGroup(GroupId.random(), name, description, List.of(), List.of(), Optional.empty());
    }

    /** Whether the client on account {@code accountUuid} is a member. */
    public boolean contains(String accountUuid) {
        return members.contains(accountUuid);
    }

    public ClientGroup withName(String newName) {
        return new ClientGroup(id, newName, description, members, unresolved, manager);
    }

    public ClientGroup withDescription(Optional<String> newDescription) {
        return new ClientGroup(id, name, newDescription, members, unresolved, manager);
    }

    public ClientGroup withManager(Optional<ManagerSlot> newManager) {
        return new ClientGroup(id, name, description, members, unresolved, newManager);
    }

    /** This group with {@code accountUuid} added last, unless it is a member already. */
    public ClientGroup withMember(String accountUuid) {
        List<String> next = new ArrayList<>(members);
        next.add(accountUuid);
        return new ClientGroup(id, name, description, next, unresolved, manager);
    }

    public ClientGroup withoutMember(String accountUuid) {
        List<String> next = new ArrayList<>(members);
        next.remove(accountUuid);
        return new ClientGroup(id, name, description, next, unresolved, manager);
    }

    public ClientGroup withoutUnresolved(String pipe) {
        List<String> next = new ArrayList<>(unresolved);
        next.remove(pipe);
        return new ClientGroup(id, name, description, members, next, manager);
    }

    /**
     * This group with the unresolved pipe {@code pipe} replaced by the account
     * now known to be on it. Unchanged when {@code pipe} is not unresolved here.
     */
    public ClientGroup resolving(String pipe, String accountUuid) {
        if (!unresolved.contains(pipe)) {
            return this;
        }
        return withoutUnresolved(pipe).withMember(accountUuid);
    }

    private static List<String> distinct(List<String> values) {
        Objects.requireNonNull(values, "values");
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
