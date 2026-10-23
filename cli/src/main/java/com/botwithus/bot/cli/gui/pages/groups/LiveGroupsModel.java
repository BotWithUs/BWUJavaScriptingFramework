package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.api.script.ClientOrchestrator.OpResult;
import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.ClientManager;
import com.botwithus.bot.cli.clients.ClientRecord;
import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.cli.groups.ManagerSlot;
import com.botwithus.bot.cli.groups.MemberChange;
import com.botwithus.bot.cli.gui.inspector.InspectorRequest;
import com.botwithus.bot.cli.gui.usermode.board.ScriptEntry;
import com.botwithus.bot.cli.management.Target;
import com.botwithus.bot.core.runtime.ManagementScriptRunner;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link GroupsModel} over the live host.
 *
 * <p>Reads are cheap and happen on the render thread. Every change runs on the
 * host's command executor, the one console commands run on: saving the groups
 * file, stopping a script and waiting for a restart all block. Starts go through
 * {@link CliContext#startWhenBack}, which starts at once on a connected client
 * and queues the start for one that is not; stops go through the
 * {@link ClientManager}, which also cancels a queued start of the same script.</p>
 */
public final class LiveGroupsModel implements GroupsModel {

    private final CliContext ctx;
    private final LiveGroupsReader reader;
    private final LiveGroupManagers managers;
    private final Supplier<List<ScriptEntry>> catalog;
    private final Executor commands;
    private final Consumer<InspectorRequest> inspector;
    private final Consumer<String> openManagement;
    private final AtomicReference<Notice> notice = new AtomicReference<>();
    private final AtomicReference<GroupId> created = new AtomicReference<>();

    /**
     * @param catalog  the installed scripts, from the last completed load; never loads on the calling thread
     * @param commands runs every change, off the render thread
     */
    public LiveGroupsModel(CliContext ctx, Supplier<List<ScriptEntry>> catalog, Executor commands) {
        this(ctx, catalog, commands, request -> { }, script -> { }, Clock.systemDefaultZone());
    }

    /**
     * @param catalog        the installed scripts, from the last completed load; never loads on the calling thread
     * @param commands       runs every change, off the render thread
     * @param inspector      opens the shared config inspector, for the manager's Settings
     * @param openManagement opens the Management page on a management script, for a robot link
     * @param clock          measures how long the manager has run
     */
    public LiveGroupsModel(CliContext ctx, Supplier<List<ScriptEntry>> catalog, Executor commands,
                           Consumer<InspectorRequest> inspector, Consumer<String> openManagement, Clock clock) {
        this.ctx = ctx;
        this.reader = new LiveGroupsReader(ctx);
        this.managers = new LiveGroupManagers(ctx, clock);
        this.catalog = catalog;
        this.commands = commands;
        this.inspector = inspector;
        this.openManagement = openManagement;
    }

    @Override
    public GroupsSnapshot snapshot() {
        return reader.read();
    }

    @Override
    public int groupCount() {
        return store().all().size();
    }

    @Override
    public List<ScriptEntry> catalog() {
        return catalog.get();
    }

    @Override
    public Optional<Notice> notice() {
        return Optional.ofNullable(notice.get());
    }

    @Override
    public void dismissNotice() {
        notice.set(null);
    }

    @Override
    public Optional<GroupId> takeCreated() {
        return Optional.ofNullable(created.getAndSet(null));
    }

    @Override
    public void createGroup(String name, List<ClientKey> members) {
        String wanted = name.strip();
        commands.execute(() -> {
            Optional<ClientGroup> group = store().create(wanted, Optional.empty());
            if (group.isEmpty()) {
                tell(Notice.problem(wanted.isEmpty() ? "A group needs a name."
                        : "There is already a group called " + wanted + "."));
                return;
            }
            created.set(group.get().id());
            if (members.isEmpty()) {
                tell(Notice.info("Created " + wanted + "."));
            } else {
                add(group.get().id(), members);
            }
        });
    }

    @Override
    public void rename(GroupId id, String name) {
        String wanted = name.strip();
        commands.execute(() -> store().get(id).ifPresent(group -> {
            if (group.name().equals(wanted)) {
                return;
            }
            if (!store().rename(id, wanted)) {
                tell(Notice.problem(wanted.isEmpty() ? "A group needs a name."
                        : "There is already a group called " + wanted + "."));
            }
        }));
    }

    @Override
    public void delete(GroupId id) {
        commands.execute(() -> store().get(id).ifPresent(group -> {
            if (store().delete(id)) {
                tell(Notice.info("Deleted " + group.name() + ". Its clients and scripts are untouched."));
            }
        }));
    }

    @Override
    public void addMembers(GroupId id, List<ClientKey> keys) {
        List<ClientKey> copy = List.copyOf(keys);
        commands.execute(() -> add(id, copy));
    }

    @Override
    public void removeMembers(GroupId id, Collection<String> rowKeys) {
        List<String> copy = List.copyOf(rowKeys);
        commands.execute(() -> store().get(id).ifPresent(group -> {
            int removed = 0;
            for (String key : copy) {
                removed += removeOne(id, key) ? 1 : 0;
            }
            tell(Notice.info("Removed " + GroupText.count(removed, "client") + " from " + group.name()
                    + ". " + (removed == 1 ? "Its scripts keep" : "Their scripts keep") + " running."));
        }));
    }

    @Override
    public void startScript(GroupId id, String script, BusyChoice choice) {
        commands.execute(() -> store().get(id).ifPresent(group -> {
            StartPlan plan = StartPlan.of(group, reader.read(), script, choice);
            if (plan.stopsOthers()) {
                plan.busy().forEach(member -> stopOthers(member, script));
            }
            plan.startsNow().forEach(member -> ctx.startWhenBack(member.uuid(), script));
            plan.queued().forEach(member -> ctx.startWhenBack(member.uuid(), script));
            tell(Notice.info(GroupNotices.started(plan)));
        }));
    }

    /** Through the management control, which pauses the group's manager before it stops anything. */
    @Override
    public void stopAll(GroupId id) {
        commands.execute(() -> store().get(id).ifPresent(group -> {
            boolean isPausing = group.manager().filter(ManagerSlot::shouldRun).isPresent();
            ctx.getManagementControl().stopAllOnGroup(id);
            tell(Notice.info("Stopped every script in " + group.name() + "."
                    + (isPausing ? " Its manager is paused, so it does not start them again." : "")));
        }));
    }

    @Override
    public void stopMembers(Collection<String> uuids) {
        List<String> copy = List.copyOf(uuids);
        commands.execute(() -> copy.forEach(this::stopMember));
    }

    @Override
    public void run(String uuid, String script) {
        commands.execute(() -> reader.pipeOf(uuid).ifPresent(pipe ->
                report(ctx.getClientManager().startScript(pipe, script), uuid, "start")));
    }

    @Override
    public void restart(String uuid, String script) {
        commands.execute(() -> reader.pipeOf(uuid).ifPresent(pipe ->
                report(ctx.getClientManager().restartScript(pipe, script), uuid, "restart")));
    }

    // ── The group's manager ────────────────────────────────────────────────

    @Override
    public Optional<ManagerInfo> manager(GroupId id) {
        return managers.manager(id);
    }

    @Override
    public List<ManagerChoice> managers() {
        return managers.managers();
    }

    @Override
    public void assignManager(GroupId id, String script, boolean isStartNow) {
        commands.execute(() -> store().get(id).ifPresent(group -> {
            if (!ctx.getManagementTargets().add(script, new Target.Group(id))) {
                return;
            }
            if (isStartNow && !managers.runner(script).map(ManagementScriptRunner::isRunning).orElse(false)) {
                ctx.getManagementControl().start(script);
            }
            tell(Notice.info(script + " now manages " + group.name() + ". It sees only this group's clients."));
        }));
    }

    @Override
    public void startManager(GroupId id) {
        commands.execute(() -> store().get(id).flatMap(ClientGroup::manager).ifPresent(slot -> {
            ctx.getManagementControl().resumeManager(id);
            if (!managers.runner(slot.script()).map(ManagementScriptRunner::isRunning).orElse(false)) {
                ctx.getManagementControl().start(slot.script());
            }
        }));
    }

    @Override
    public void openManagerSettings(GroupId id) {
        store().get(id).flatMap(ClientGroup::manager)
                .flatMap(slot -> managers.runner(slot.script()))
                .ifPresent(runner -> inspector.accept(InspectorRequest.forManagementTarget(runner,
                        new Target.Group(id))));
    }

    @Override
    public Optional<MemberManagement> memberManagement(GroupId id, String uuid, String script) {
        return managers.memberManagement(id, uuid, script);
    }

    @Override
    public void openManagement(String script) {
        openManagement.accept(script);
    }

    // ── On the command executor ────────────────────────────────────────────

    private void add(GroupId id, List<ClientKey> keys) {
        List<String> added = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (ClientKey key : keys) {
            switch (store().addMember(id, key)) {
                case MemberChange.Added _, MemberChange.AlreadyMember _ -> added.add(nameOf(key));
                case MemberChange.Refused refusal -> refused.add(nameOf(key) + ": " + refusal.reason());
                case MemberChange.NoSuchGroup _ -> {
                    tell(Notice.problem("That group no longer exists."));
                    return;
                }
            }
        }
        String group = store().get(id).map(ClientGroup::name).orElse("the group");
        tell(GroupNotices.added(group, added, refused));
    }

    private boolean removeOne(GroupId id, String rowKey) {
        if (rowKey.startsWith(ClientKey.PIPE_PREFIX)) {
            return store().removeUnresolved(id, rowKey.substring(ClientKey.PIPE_PREFIX.length()));
        }
        return store().removeMember(id, rowKey);
    }

    private void stopOthers(StartPlan.Member member, String script) {
        reader.pipeOf(member.uuid()).ifPresent(pipe -> member.others().stream()
                .filter(other -> !other.equalsIgnoreCase(script))
                .forEach(other -> ctx.getClientManager().stopScript(pipe, other)));
    }

    /** Stops what runs on the member and cancels what waits for it. */
    private void stopMember(String uuid) {
        MemberFacts facts = reader.facts(uuid);
        Optional<String> pipe = reader.pipeOf(uuid);
        ClientManager manager = ctx.getClientManager();
        for (ScriptFact fact : facts.scripts()) {
            if (fact.state() == ScriptState.QUEUED) {
                manager.stopScript(pipe.orElse(uuid), fact.name());
            } else if (fact.state().isActive() && pipe.isPresent()) {
                manager.stopScript(pipe.get(), fact.name());
            }
        }
    }

    private void report(OpResult result, String uuid, String verb) {
        if (!result.success()) {
            tell(Notice.problem("Could not " + verb + " " + result.scriptName() + " on "
                    + GroupText.accountOf(reader.facts(uuid)) + ": " + result.message() + "."));
        }
    }

    private String nameOf(ClientKey key) {
        return ctx.getClientRegistry().get(key).flatMap(ClientRecord::name).orElseGet(() -> GroupText.idOf(key));
    }

    private void tell(Notice next) {
        notice.set(next);
    }

    private GroupStore store() {
        return ctx.getGroupStore();
    }
}
