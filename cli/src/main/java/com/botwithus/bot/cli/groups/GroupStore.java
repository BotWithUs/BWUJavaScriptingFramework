package com.botwithus.bot.cli.groups;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.GroupsFile.Contents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The host's groups, kept in {@code groups.json}.
 *
 * <p>Every change is saved before the call returns. Names are unique, so the
 * orchestrator API, which names groups, can resolve one; the {@link GroupId}
 * is what everything else should hold on to, since a rename keeps it.</p>
 *
 * <p>Thread-safe. Reads never block: they return the groups as an immutable
 * snapshot, in the order they were created, safe to use on the render thread.
 * Changes are serialised, and each one saves the state that includes it, so
 * the file always ends up holding the latest.</p>
 */
public final class GroupStore {

    private static final Logger log = LoggerFactory.getLogger(GroupStore.class);

    private final GroupsFile file;
    /** Serialises changes and the saves that follow them. */
    private final Object lock = new Object();
    /** Replaced whole under {@link #lock}; read without it. */
    private volatile List<ClientGroup> groups = List.of();

    public GroupStore(GroupsFile file) {
        this.file = file;
    }

    /**
     * Replaces the groups with the ones saved in the file. A file in the
     * name-keyed format is migrated ({@link GroupMigration}), backed up as
     * {@value GroupsFile#LEGACY_BACKUP_NAME}, and saved in the current format.
     * A file that cannot be read is logged and leaves no groups.
     *
     * @param accountOnPipe the account of the client identified on a pipe now,
     *                      for resolving the members of a name-keyed file
     */
    public void load(Function<String, Optional<String>> accountOnPipe) {
        synchronized (lock) {
            Contents contents;
            try {
                contents = file.read();
            } catch (IOException e) {
                log.error("Failed to load groups", e);
                return;
            }
            groups = switch (contents) {
                case Contents.Missing _ -> List.of();
                case Contents.Current current -> current.groups();
                case Contents.Legacy legacy -> migrate(legacy, accountOnPipe);
            };
        }
    }

    /** Every group, in the order they were created. */
    public List<ClientGroup> all() {
        return groups;
    }

    public Optional<ClientGroup> get(GroupId id) {
        return groups.stream().filter(group -> group.id().equals(id)).findFirst();
    }

    /** The group called exactly {@code name}. */
    public Optional<ClientGroup> byName(String name) {
        return groups.stream().filter(group -> group.name().equals(name)).findFirst();
    }

    /** The groups the client on account {@code accountUuid} is in. */
    public List<ClientGroup> groupsOf(String accountUuid) {
        return groups.stream().filter(group -> group.contains(accountUuid)).toList();
    }

    /**
     * Creates an empty group.
     *
     * @return the group; empty if {@code name} is blank or another group has it
     */
    public Optional<ClientGroup> create(String name, Optional<String> description) {
        synchronized (lock) {
            if (name.isBlank() || byName(name).isPresent()) {
                return Optional.empty();
            }
            ClientGroup created = ClientGroup.create(name, description);
            List<ClientGroup> next = new ArrayList<>(groups);
            next.add(created);
            commit(next);
            return Optional.of(created);
        }
    }

    /** @return {@code false} if there is no such group, or {@code name} is blank or taken */
    public boolean rename(GroupId id, String name) {
        synchronized (lock) {
            boolean isTaken = byName(name).filter(other -> !other.id().equals(id)).isPresent();
            if (name.isBlank() || isTaken) {
                return false;
            }
            return change(id, group -> group.withName(name));
        }
    }

    /** @return {@code false} if there is no such group */
    public boolean setDescription(GroupId id, Optional<String> description) {
        return change(id, group -> group.withDescription(description));
    }

    /** Assigns, or with an empty {@code manager} clears, the group's manager. */
    public boolean setManager(GroupId id, Optional<ManagerSlot> manager) {
        return change(id, group -> group.withManager(manager));
    }

    /** @return {@code false} if there was no such group */
    public boolean delete(GroupId id) {
        synchronized (lock) {
            List<ClientGroup> next = new ArrayList<>(groups);
            if (!next.removeIf(group -> group.id().equals(id))) {
                return false;
            }
            commit(next);
            return true;
        }
    }

    /**
     * Adds the client known by {@code key}. Only a client on a real account, and
     * only the first one open on it, can be a member; any other is refused with
     * the reason, see {@link MemberChange#refusalFor}.
     */
    public MemberChange addMember(GroupId id, ClientKey key) {
        Optional<MemberChange.Refused> refusal = MemberChange.refusalFor(key);
        if (refusal.isPresent()) {
            return refusal.get();
        }
        String uuid = key.accountUuid().orElseThrow();
        synchronized (lock) {
            Optional<ClientGroup> group = get(id);
            if (group.isEmpty()) {
                return new MemberChange.NoSuchGroup();
            }
            if (group.get().contains(uuid)) {
                return new MemberChange.AlreadyMember();
            }
            change(id, current -> current.withMember(uuid));
            return new MemberChange.Added(get(id).orElseThrow());
        }
    }

    /** @return whether the client on account {@code accountUuid} was a member */
    public boolean removeMember(GroupId id, String accountUuid) {
        return change(id, group -> group.withoutMember(accountUuid));
    }

    /** Removes an unresolved member carried over from the name-keyed file. */
    public boolean removeUnresolved(GroupId id, String pipe) {
        return change(id, group -> group.withoutUnresolved(pipe));
    }

    /** Whether any group still has {@code pipe} as an unresolved member. */
    public boolean isUnresolved(String pipe) {
        return groups.stream().anyMatch(group -> group.unresolved().contains(pipe));
    }

    /**
     * The client on {@code pipe} turned out to be on account {@code accountUuid}:
     * every group holding {@code pipe} as an unresolved member now has the
     * account instead.
     *
     * @return how many groups changed
     */
    public int resolve(String pipe, String accountUuid) {
        synchronized (lock) {
            List<ClientGroup> next = groups.stream().map(group -> group.resolving(pipe, accountUuid)).toList();
            int changed = countChanged(groups, next);
            if (changed > 0) {
                commit(next);
                log.info("Resolved pipe {} to account {} in {} group(s)", pipe, accountUuid, changed);
            }
            return changed;
        }
    }

    private List<ClientGroup> migrate(Contents.Legacy legacy, Function<String, Optional<String>> accountOnPipe) {
        List<ClientGroup> migrated = GroupMigration.migrate(legacy.groups(), accountOnPipe);
        try {
            file.backUpLegacy(legacy.text());
            file.write(migrated);
            log.info("Migrated {} group(s) to account members; the old file is kept as {}",
                    migrated.size(), GroupsFile.LEGACY_BACKUP_NAME);
        } catch (IOException e) {
            log.error("Migrated groups could not be saved; the old file is left as it was", e);
        }
        return migrated;
    }

    /** Applies {@code edit} to group {@code id} and saves, if that changes it. */
    private boolean change(GroupId id, UnaryOperator<ClientGroup> edit) {
        synchronized (lock) {
            List<ClientGroup> next = groups.stream()
                    .map(group -> group.id().equals(id) ? edit.apply(group) : group)
                    .toList();
            if (countChanged(groups, next) == 0) {
                return false;
            }
            commit(next);
            return true;
        }
    }

    private static int countChanged(List<ClientGroup> before, List<ClientGroup> after) {
        int changed = 0;
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).equals(after.get(i))) {
                changed++;
            }
        }
        return changed;
    }

    /** Publishes {@code next} and saves it. Call with {@link #lock} held. */
    private void commit(List<ClientGroup> next) {
        groups = List.copyOf(next);
        try {
            file.write(groups);
        } catch (IOException e) {
            log.error("Failed to save groups", e);
        }
    }
}
