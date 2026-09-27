package com.botwithus.bot.cli.management;

import com.botwithus.bot.api.script.ManagementTarget;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.cli.groups.ManagerSlot;
import com.botwithus.bot.cli.management.ManagementFile.Contents;
import com.botwithus.bot.cli.management.ManagementFile.Stored;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * What each management script manages, and whether it should be running.
 *
 * <p>A script can manage any mix of the whole host, groups and single client
 * scripts. The whole host covers every client, so adding it clears the rest,
 * and adding anything else clears it. A script with no targets is "not
 * applied": it may run, but its orchestrator sees nothing.</p>
 *
 * <p>A group target is the group's manager slot in {@link GroupStore}: adding
 * one assigns the script as the group's manager, replacing any other, and
 * removing it clears the slot. Assigning a manager on the group has the same
 * effect the other way round. A group keeps its id when it is renamed, so a
 * rename leaves the target in place. The rest is kept in
 * {@code management.json}; see {@link ManagementFile}.</p>
 *
 * <p>The file is read on first use. On a host that has never kept targets, the
 * scripts found by the first load pass manage the whole host, as every
 * management script did before targets existed; see {@link #recordLoaded}.</p>
 *
 * <p>Thread-safe. Reads do not block; changes are serialised and saved before
 * they return.</p>
 */
public final class ManagementTargets {

    private static final Logger log = LoggerFactory.getLogger(ManagementTargets.class);

    /**
     * A management script that covers a client script, and the target that does it.
     *
     * @param managementScript the management script's name
     * @param via              its most specific target covering the client script
     */
    public record ManagedBy(String managementScript, Target via) {
        public ManagedBy {
            Objects.requireNonNull(managementScript, "managementScript");
            Objects.requireNonNull(via, "via");
        }
    }

    private final ManagementFile file;
    private final GroupStore groups;
    /** Serialises loading, changes and the saves that follow them. */
    private final Object lock = new Object();
    /** Replaced whole under {@link #lock}; {@code null} until the file is read. */
    private volatile Map<String, Stored> scripts;
    /** Guarded by {@link #lock}. */
    private boolean isMigrationPending;

    public ManagementTargets(ManagementFile file, GroupStore groups) {
        this.file = Objects.requireNonNull(file, "file");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    /** {@code script}'s targets: the whole host first, then its groups, then its client scripts. */
    public List<Target> targetsOf(String script) {
        Stored stored = stored(script);
        List<Target> targets = new ArrayList<>();
        if (stored.isWholeHost()) {
            targets.add(Target.host());
        }
        managedGroups(script).forEach(group -> targets.add(new Target.Group(group.id())));
        targets.addAll(stored.clientScripts());
        return List.copyOf(targets);
    }

    /** What {@code script}'s targets cover now, for its orchestrator's next call. */
    public Scope scopeOf(String script) {
        Stored stored = stored(script);
        return new Scope(stored.isWholeHost(), managedGroups(script), stored.clientScripts());
    }

    /** Whether {@code script} has no targets at all. */
    public boolean isNotApplied(String script) {
        return targetsOf(script).isEmpty();
    }

    /** Whether the host should keep {@code script} running. */
    public boolean isDesiredRunning(String script) {
        return stored(script).desiredRunning();
    }

    /**
     * {@code script}'s targets as scripts see them. A group is named as it is
     * called now; a group that no longer exists is left out.
     */
    public Set<ManagementTarget> apiTargetsOf(String script) {
        Set<ManagementTarget> targets = new LinkedHashSet<>();
        for (Target target : targetsOf(script)) {
            switch (target) {
                case Target.Host _ -> targets.add(new ManagementTarget.WholeHost());
                case Target.Group group -> groups.get(group.id()).ifPresent(found ->
                        targets.add(new ManagementTarget.Group(found.id().toString(), found.name())));
                case Target.ClientScript cs ->
                        targets.add(new ManagementTarget.ClientScript(cs.accountUuid(), cs.scriptName()));
            }
        }
        return Set.copyOf(targets);
    }

    /**
     * The management scripts whose targets cover {@code scriptName} on the
     * client on account {@code accountUuid}: those naming it directly first,
     * then by group, then by the whole host; by name within each.
     */
    public List<ManagedBy> managedBy(String accountUuid, String scriptName) {
        Optional<String> account = Optional.of(accountUuid);
        List<ManagedBy> found = new ArrayList<>();
        for (String script : knownScripts()) {
            if (scopeOf(script).covers(account, scriptName)) {
                found.add(new ManagedBy(script, mostSpecific(script, accountUuid, scriptName)));
            }
        }
        found.sort(Comparator.comparingInt((ManagedBy by) -> specificity(by.via()))
                .thenComparing(ManagedBy::managementScript));
        return List.copyOf(found);
    }

    /** Every management script that has targets or should be running, in name order. */
    public List<String> knownScripts() {
        Set<String> names = new TreeSet<>(current().keySet());
        groups.all().forEach(group -> group.manager().ifPresent(slot -> names.add(slot.script())));
        return List.copyOf(names);
    }

    // ── Changes ─────────────────────────────────────────────────────────────

    /**
     * Adds {@code target} to {@code script}'s targets. The whole host replaces
     * every other target; anything else replaces the whole host. A group target
     * makes the script the group's manager, running, in place of any other.
     *
     * @return whether anything changed; {@code false} for a group that does not exist
     */
    public boolean add(String script, Target target) {
        return switch (target) {
            case Target.Host _ -> addWholeHost(script);
            case Target.Group group -> addGroup(script, group.id());
            case Target.ClientScript clientScript -> edit(script, stored -> {
                List<Target.ClientScript> next = new ArrayList<>(stored.clientScripts());
                if (!next.contains(clientScript)) {
                    next.add(clientScript);
                }
                return new Stored(false, next, stored.desiredRunning());
            });
        };
    }

    /**
     * Removes {@code target} from {@code script}'s targets. Removing a group
     * clears the group's manager slot, if the script holds it.
     *
     * @return whether anything changed
     */
    public boolean remove(String script, Target target) {
        return switch (target) {
            case Target.Host _ -> edit(script, stored ->
                    new Stored(false, stored.clientScripts(), stored.desiredRunning()));
            case Target.Group group -> isManagerOf(script, group.id())
                    && groups.setManager(group.id(), Optional.empty());
            case Target.ClientScript clientScript -> edit(script, stored -> {
                List<Target.ClientScript> next = new ArrayList<>(stored.clientScripts());
                next.remove(clientScript);
                return new Stored(stored.isWholeHost(), next, stored.desiredRunning());
            });
        };
    }

    /** Records whether the host should keep {@code script} running. */
    public boolean setDesiredRunning(String script, boolean isRunning) {
        return edit(script, stored -> new Stored(stored.isWholeHost(), stored.clientScripts(), isRunning));
    }

    /**
     * Pauses or resumes the manager of group {@code id}. A paused manager may
     * still see its group and stop scripts on it, but starts nothing there.
     *
     * @return whether anything changed; {@code false} if the group has no manager
     */
    public boolean setManagerPaused(GroupId id, boolean isPaused) {
        Optional<ManagerSlot> slot = groups.get(id).flatMap(ClientGroup::manager);
        if (slot.isEmpty() || slot.get().shouldRun() == !isPaused) {
            return false;
        }
        return groups.setManager(id, Optional.of(new ManagerSlot(slot.get().script(), !isPaused)));
    }

    /**
     * Notes the management scripts a load pass found. On a host that has never
     * kept targets, each of them that has none yet manages the whole host, as
     * every management script did before targets existed; that happens once,
     * for the first load pass, and scripts found later start not applied.
     */
    public void recordLoaded(Collection<String> loaded) {
        synchronized (lock) {
            Map<String, Stored> known = current();
            if (!isMigrationPending) {
                return;
            }
            Map<String, Stored> next = new HashMap<>(known);
            int given = 0;
            for (String script : loaded) {
                if (targetsOf(script).isEmpty()) {
                    Stored stored = next.getOrDefault(script, Stored.NONE);
                    next.put(script, new Stored(true, List.of(), stored.desiredRunning()));
                    given++;
                }
            }
            isMigrationPending = false;
            commit(next);
            log.info("First management load: {} script(s) given the whole host, as before targets existed",
                    given);
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private boolean addWholeHost(String script) {
        boolean isChanged = edit(script, stored -> new Stored(true, List.of(), stored.desiredRunning()));
        for (ClientGroup group : managedGroups(script)) {
            isChanged |= groups.setManager(group.id(), Optional.empty());
        }
        return isChanged;
    }

    private boolean addGroup(String script, GroupId id) {
        if (groups.get(id).isEmpty()) {
            return false;
        }
        boolean isChanged = edit(script, stored ->
                new Stored(false, stored.clientScripts(), stored.desiredRunning()));
        if (!isManagerOf(script, id)) {
            isChanged |= groups.setManager(id, Optional.of(new ManagerSlot(script, true)));
        }
        return isChanged;
    }

    private boolean isManagerOf(String script, GroupId id) {
        return groups.get(id).flatMap(ClientGroup::manager)
                .filter(slot -> slot.script().equals(script)).isPresent();
    }

    /** The groups whose manager slot holds {@code script}, in the order they were created. */
    private List<ClientGroup> managedGroups(String script) {
        return groups.all().stream()
                .filter(group -> group.manager().filter(slot -> slot.script().equals(script)).isPresent())
                .toList();
    }

    private Target mostSpecific(String script, String accountUuid, String scriptName) {
        Target.ClientScript direct = new Target.ClientScript(accountUuid, scriptName);
        if (stored(script).clientScripts().contains(direct)) {
            return direct;
        }
        return managedGroups(script).stream()
                .filter(group -> group.contains(accountUuid))
                .<Target>map(group -> new Target.Group(group.id()))
                .findFirst()
                .orElseGet(Target::host);
    }

    private static int specificity(Target target) {
        return switch (target) {
            case Target.ClientScript _ -> 0;
            case Target.Group _ -> 1;
            case Target.Host _ -> 2;
        };
    }

    private Stored stored(String script) {
        return current().getOrDefault(script, Stored.NONE);
    }

    /** Applies {@code change} to {@code script}'s entry and saves, if that changes it. */
    private boolean edit(String script, UnaryOperator<Stored> change) {
        synchronized (lock) {
            Map<String, Stored> known = current();
            Stored before = known.getOrDefault(script, Stored.NONE);
            Stored after = change.apply(before);
            if (after.equals(before)) {
                return false;
            }
            Map<String, Stored> next = new HashMap<>(known);
            next.put(script, after);
            commit(next);
            return true;
        }
    }

    /** The entries, reading the file on first use. */
    private Map<String, Stored> current() {
        Map<String, Stored> loaded = scripts;
        if (loaded != null) {
            return loaded;
        }
        synchronized (lock) {
            if (scripts == null) {
                load();
            }
            return scripts;
        }
    }

    /** Call with {@link #lock} held. */
    private void load() {
        Contents contents;
        try {
            contents = file.read();
        } catch (IOException e) {
            log.error("Failed to read the management targets; every script starts not applied", e);
            contents = new Contents.Current(Map.of(), false);
        }
        switch (contents) {
            case Contents.Missing _ -> {
                scripts = Map.of();
                isMigrationPending = true;
            }
            case Contents.Current current -> {
                scripts = current.scripts();
                isMigrationPending = current.isMigrationPending();
            }
        }
    }

    /** Publishes {@code next} and saves it. Call with {@link #lock} held. */
    private void commit(Map<String, Stored> next) {
        scripts = Map.copyOf(next);
        try {
            file.write(scripts, isMigrationPending);
        } catch (IOException e) {
            log.error("Failed to save the management targets", e);
        }
    }
}
