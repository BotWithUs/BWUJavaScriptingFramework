package com.botwithus.bot.cli.management;

import com.botwithus.bot.api.script.ClientOrchestrator;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The orchestrator one management script is given: the host's orchestrator,
 * limited to the script's targets, with every action recorded.
 *
 * <p>Queries leave out the clients, scripts, groups and schedules the targets
 * do not cover. A call that would act on one of those is not passed on: it
 * returns a failed result saying {@value #NOT_IN_TARGETS}, and never throws. A
 * call over a group or over every client acts on the covered part only. Only a
 * script managing the whole host may create, delete or change groups.</p>
 *
 * <p>A script that manages a group but has been paused on it (the user stopped
 * everything on the group) still sees the group and may stop scripts on it, but
 * nothing it asks to start or schedule there is started: the user stopped
 * those clients on purpose.</p>
 *
 * <p>Every call that acts, or was refused, goes into the {@link OrchestratorAuditLog}.</p>
 *
 * <p>The targets are read again for every call, so a change applies at once.</p>
 */
public final class ScopedClientOrchestrator implements ClientOrchestrator {

    /** Why a call on something outside the script's targets failed. */
    public static final String NOT_IN_TARGETS = "not in this script's targets";
    /** Why a start on a group the script is paused on failed. */
    public static final String PAUSED = "this script is paused on the client's group";
    /** Why a change to groups failed. */
    public static final String GROUPS_NEED_WHOLE_HOST = "changing groups needs the whole host as a target";

    private static final String REFUSED = "refused: ";
    private static final String FAILED = "failed: ";
    private static final String DONE = "done";
    private static final String NO_CHANGE = "no change";
    private static final String EVERY_CLIENT = "every client";
    private static final String GROUP = "group ";

    private final String script;
    private final ClientOrchestrator delegate;
    private final Supplier<Scope> scope;
    private final Function<String, Optional<String>> accountOf;
    private final OrchestratorAuditLog audit;

    /**
     * @param script    the management script this orchestrator is for
     * @param delegate  the host's orchestrator, over every client
     * @param scope     the script's targets as they are now; asked on every call
     * @param accountOf the account UUID of the client a name means: a connected
     *                  client's pipe, or an account UUID; empty for a client with
     *                  no account, which only the whole host covers
     * @param audit     where every action is recorded
     */
    public ScopedClientOrchestrator(String script, ClientOrchestrator delegate, Supplier<Scope> scope,
                                    Function<String, Optional<String>> accountOf, OrchestratorAuditLog audit) {
        this.script = Objects.requireNonNull(script, "script");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.accountOf = Objects.requireNonNull(accountOf, "accountOf");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    // ── Client queries ──────────────────────────────────────────────────────

    @Override
    public List<String> getClientNames() {
        Scope now = scope.get();
        return delegate.getClientNames().stream()
                .filter(name -> now.coversClient(accountOf.apply(name)))
                .toList();
    }

    @Override
    public boolean isClientAlive(String name) {
        return scope.get().coversClient(accountOf.apply(name)) && delegate.isClientAlive(name);
    }

    // ── Group management ────────────────────────────────────────────────────

    @Override
    public boolean createGroup(String name, String description) {
        return changeGroups("createGroup", GROUP + name, () -> delegate.createGroup(name, description));
    }

    @Override
    public boolean deleteGroup(String name) {
        return changeGroups("deleteGroup", GROUP + name, () -> delegate.deleteGroup(name));
    }

    @Override
    public Set<String> getGroupNames() {
        Scope now = scope.get();
        return delegate.getGroupNames().stream()
                .filter(now::seesGroup)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public String getGroupDescription(String groupName) {
        return scope.get().seesGroup(groupName) ? delegate.getGroupDescription(groupName) : null;
    }

    @Override
    public void setGroupDescription(String groupName, String description) {
        changeGroups("setGroupDescription", GROUP + groupName, () -> {
            delegate.setGroupDescription(groupName, description);
            return true;
        });
    }

    @Override
    public Set<String> getGroupMembers(String groupName) {
        return scope.get().seesGroup(groupName) ? delegate.getGroupMembers(groupName) : Set.of();
    }

    @Override
    public boolean addToGroup(String groupName, String clientName) {
        return changeGroups("addToGroup", clientName + " to " + GROUP + groupName,
                () -> delegate.addToGroup(groupName, clientName));
    }

    @Override
    public boolean removeFromGroup(String groupName, String clientName) {
        return changeGroups("removeFromGroup", clientName + " from " + GROUP + groupName,
                () -> delegate.removeFromGroup(groupName, clientName));
    }

    // ── Single-client script operations ─────────────────────────────────────

    @Override
    public OpResult startScript(String clientName, String scriptName) {
        return audited("startScript", on(scriptName, clientName),
                () -> startOne(scope.get(), clientName, scriptName, delegate::startScript),
                ScopedClientOrchestrator::summary);
    }

    @Override
    public OpResult stopScript(String clientName, String scriptName) {
        return audited("stopScript", on(scriptName, clientName),
                () -> stopOne(scope.get(), clientName, scriptName), ScopedClientOrchestrator::summary);
    }

    @Override
    public OpResult restartScript(String clientName, String scriptName) {
        return audited("restartScript", on(scriptName, clientName),
                () -> startOne(scope.get(), clientName, scriptName, delegate::restartScript),
                ScopedClientOrchestrator::summary);
    }

    // ── Group script operations ─────────────────────────────────────────────

    @Override
    public List<OpResult> startScriptOnGroup(String groupName, String scriptName) {
        return audited("startScriptOnGroup", on(scriptName, GROUP + groupName), () -> startOnGroup(groupName,
                scriptName, () -> delegate.startScriptOnGroup(groupName, scriptName), delegate::startScript),
                ScopedClientOrchestrator::summaryOfOps);
    }

    @Override
    public List<OpResult> stopScriptOnGroup(String groupName, String scriptName) {
        return audited("stopScriptOnGroup", on(scriptName, GROUP + groupName),
                () -> stopOnGroup(groupName, scriptName, () -> delegate.stopScriptOnGroup(groupName, scriptName)),
                ScopedClientOrchestrator::summaryOfOps);
    }

    @Override
    public List<OpResult> restartScriptOnGroup(String groupName, String scriptName) {
        return audited("restartScriptOnGroup", on(scriptName, GROUP + groupName), () -> startOnGroup(groupName,
                scriptName, () -> delegate.restartScriptOnGroup(groupName, scriptName), delegate::restartScript),
                ScopedClientOrchestrator::summaryOfOps);
    }

    @Override
    public List<OpResult> stopAllScriptsOnGroup(String groupName) {
        return audited("stopAllScriptsOnGroup", GROUP + groupName,
                () -> stopOnGroup(groupName, null, () -> delegate.stopAllScriptsOnGroup(groupName)),
                ScopedClientOrchestrator::summaryOfOps);
    }

    // ── All-client script operations ────────────────────────────────────────

    @Override
    public List<OpResult> startScriptOnAll(String scriptName) {
        return audited("startScriptOnAll", on(scriptName, EVERY_CLIENT),
                () -> startOnAll(scriptName, () -> delegate.startScriptOnAll(scriptName), delegate::startScript),
                ScopedClientOrchestrator::summaryOfOps);
    }

    @Override
    public List<OpResult> stopScriptOnAll(String scriptName) {
        return audited("stopScriptOnAll", on(scriptName, EVERY_CLIENT), () -> {
            Scope now = scope.get();
            if (now.isWholeHost()) {
                return delegate.stopScriptOnAll(scriptName);
            }
            return coveredLiveClients(now, scriptName).stream()
                    .map(client -> delegate.stopScript(client, scriptName))
                    .toList();
        }, ScopedClientOrchestrator::summaryOfOps);
    }

    @Override
    public List<OpResult> restartScriptOnAll(String scriptName) {
        return audited("restartScriptOnAll", on(scriptName, EVERY_CLIENT),
                () -> startOnAll(scriptName, () -> delegate.restartScriptOnAll(scriptName), delegate::restartScript),
                ScopedClientOrchestrator::summaryOfOps);
    }

    @Override
    public void stopAllScriptsOnAll() {
        audited("stopAllScriptsOnAll", EVERY_CLIENT, () -> {
            Scope now = scope.get();
            if (now.isWholeHost()) {
                delegate.stopAllScriptsOnAll();
                return DONE;
            }
            delegate.getStatusAll().stream()
                    .filter(ScriptStatusEntry::running)
                    .filter(entry -> now.covers(accountOf.apply(entry.clientName()), entry.scriptName()))
                    .forEach(entry -> delegate.stopScript(entry.clientName(), entry.scriptName()));
            return DONE;
        }, Function.identity());
    }

    // ── Single-client schedule operations ───────────────────────────────────

    @Override
    public ScheduleOpResult scheduleScript(String clientName, String scriptName, Duration delay) {
        return audited("scheduleScript", on(scriptName, clientName) + " in " + delay,
                () -> scheduleOne(scope.get(), clientName, scriptName, (c, s) -> delegate.scheduleScript(c, s, delay)),
                ScopedClientOrchestrator::summary);
    }

    @Override
    public ScheduleOpResult scheduleScript(String clientName, String scriptName, Duration delay,
                                           Map<String, Object> config) {
        return audited("scheduleScript", on(scriptName, clientName) + " in " + delay,
                () -> scheduleOne(scope.get(), clientName, scriptName,
                        (c, s) -> delegate.scheduleScript(c, s, delay, config)),
                ScopedClientOrchestrator::summary);
    }

    @Override
    public ScheduleOpResult scheduleScriptAt(String clientName, String scriptName, Instant at) {
        return audited("scheduleScriptAt", on(scriptName, clientName) + " at " + at,
                () -> scheduleOne(scope.get(), clientName, scriptName, (c, s) -> delegate.scheduleScriptAt(c, s, at)),
                ScopedClientOrchestrator::summary);
    }

    @Override
    public ScheduleOpResult scheduleScriptEvery(String clientName, String scriptName, Duration interval) {
        return audited("scheduleScriptEvery", on(scriptName, clientName) + " every " + interval,
                () -> scheduleOne(scope.get(), clientName, scriptName,
                        (c, s) -> delegate.scheduleScriptEvery(c, s, interval)),
                ScopedClientOrchestrator::summary);
    }

    @Override
    public ScheduleOpResult scheduleScriptEvery(String clientName, String scriptName, Duration interval,
                                                Duration maxDuration) {
        return audited("scheduleScriptEvery", on(scriptName, clientName) + " every " + interval,
                () -> scheduleOne(scope.get(), clientName, scriptName,
                        (c, s) -> delegate.scheduleScriptEvery(c, s, interval, maxDuration)),
                ScopedClientOrchestrator::summary);
    }

    // ── Group schedule operations ───────────────────────────────────────────

    @Override
    public List<ScheduleOpResult> scheduleScriptOnGroup(String groupName, String scriptName, Duration delay) {
        return scheduleGroup("scheduleScriptOnGroup", groupName, scriptName,
                () -> delegate.scheduleScriptOnGroup(groupName, scriptName, delay),
                (c, s) -> delegate.scheduleScript(c, s, delay));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnGroup(String groupName, String scriptName, Duration delay,
                                                        Map<String, Object> config) {
        return scheduleGroup("scheduleScriptOnGroup", groupName, scriptName,
                () -> delegate.scheduleScriptOnGroup(groupName, scriptName, delay, config),
                (c, s) -> delegate.scheduleScript(c, s, delay, config));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnGroupAt(String groupName, String scriptName, Instant at) {
        return scheduleGroup("scheduleScriptOnGroupAt", groupName, scriptName,
                () -> delegate.scheduleScriptOnGroupAt(groupName, scriptName, at),
                (c, s) -> delegate.scheduleScriptAt(c, s, at));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnGroupEvery(String groupName, String scriptName,
                                                             Duration interval) {
        return scheduleGroup("scheduleScriptOnGroupEvery", groupName, scriptName,
                () -> delegate.scheduleScriptOnGroupEvery(groupName, scriptName, interval),
                (c, s) -> delegate.scheduleScriptEvery(c, s, interval));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnGroupEvery(String groupName, String scriptName,
                                                             Duration interval, Duration maxDuration) {
        return scheduleGroup("scheduleScriptOnGroupEvery", groupName, scriptName,
                () -> delegate.scheduleScriptOnGroupEvery(groupName, scriptName, interval, maxDuration),
                (c, s) -> delegate.scheduleScriptEvery(c, s, interval, maxDuration));
    }

    // ── All-client schedule operations ──────────────────────────────────────

    @Override
    public List<ScheduleOpResult> scheduleScriptOnAll(String scriptName, Duration delay) {
        return scheduleAll("scheduleScriptOnAll", scriptName,
                () -> delegate.scheduleScriptOnAll(scriptName, delay),
                (c, s) -> delegate.scheduleScript(c, s, delay));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnAll(String scriptName, Duration delay,
                                                      Map<String, Object> config) {
        return scheduleAll("scheduleScriptOnAll", scriptName,
                () -> delegate.scheduleScriptOnAll(scriptName, delay, config),
                (c, s) -> delegate.scheduleScript(c, s, delay, config));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnAllAt(String scriptName, Instant at) {
        return scheduleAll("scheduleScriptOnAllAt", scriptName,
                () -> delegate.scheduleScriptOnAllAt(scriptName, at),
                (c, s) -> delegate.scheduleScriptAt(c, s, at));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnAllEvery(String scriptName, Duration interval) {
        return scheduleAll("scheduleScriptOnAllEvery", scriptName,
                () -> delegate.scheduleScriptOnAllEvery(scriptName, interval),
                (c, s) -> delegate.scheduleScriptEvery(c, s, interval));
    }

    @Override
    public List<ScheduleOpResult> scheduleScriptOnAllEvery(String scriptName, Duration interval,
                                                           Duration maxDuration) {
        return scheduleAll("scheduleScriptOnAllEvery", scriptName,
                () -> delegate.scheduleScriptOnAllEvery(scriptName, interval, maxDuration),
                (c, s) -> delegate.scheduleScriptEvery(c, s, interval, maxDuration));
    }

    // ── Schedule cancellation ───────────────────────────────────────────────

    @Override
    public boolean cancelSchedule(String clientName, String scheduleId) {
        Optional<Boolean> cancelled = audited("cancelSchedule", scheduleId + " on " + clientName, () -> {
            Optional<ScheduledScriptEntry> entry = delegate.listScheduledForClient(clientName).stream()
                    .filter(scheduled -> scheduled.scheduleId().equals(scheduleId))
                    .findFirst();
            boolean isCovered = entry.filter(scheduled ->
                    scope.get().covers(accountOf.apply(clientName), scheduled.scriptName())).isPresent();
            return isCovered ? Optional.of(delegate.cancelSchedule(clientName, scheduleId)) : Optional.empty();
        }, ScopedClientOrchestrator::summaryOfCancel);
        return cancelled.orElse(false);
    }

    @Override
    public List<ScheduleOpResult> cancelAllSchedulesOnGroup(String groupName) {
        return audited("cancelAllSchedulesOnGroup", GROUP + groupName,
                () -> scope.get().seesGroup(groupName)
                        ? delegate.cancelAllSchedulesOnGroup(groupName)
                        : List.of(refusedSchedule(groupName, null, NOT_IN_TARGETS)),
                ScopedClientOrchestrator::summaryOfSchedules);
    }

    @Override
    public void cancelAllSchedules() {
        audited("cancelAllSchedules", EVERY_CLIENT, () -> {
            Scope now = scope.get();
            if (now.isWholeHost()) {
                delegate.cancelAllSchedules();
                return DONE;
            }
            delegate.listScheduled().stream()
                    .filter(entry -> now.covers(accountOf.apply(entry.clientName()), entry.scriptName()))
                    .forEach(entry -> delegate.cancelSchedule(entry.clientName(), entry.scheduleId()));
            return DONE;
        }, Function.identity());
    }

    // ── Schedule queries ────────────────────────────────────────────────────

    @Override
    public List<ScheduledScriptEntry> listScheduled() {
        return coveredSchedules(delegate.listScheduled());
    }

    @Override
    public List<ScheduledScriptEntry> listScheduledForClient(String clientName) {
        return coveredSchedules(delegate.listScheduledForClient(clientName));
    }

    @Override
    public List<ScheduledScriptEntry> listScheduledForGroup(String groupName) {
        return scope.get().seesGroup(groupName) ? delegate.listScheduledForGroup(groupName) : List.of();
    }

    // ── Status ──────────────────────────────────────────────────────────────

    @Override
    public List<ScriptStatusEntry> getStatusAll() {
        Scope now = scope.get();
        return delegate.getStatusAll().stream()
                .filter(entry -> now.covers(accountOf.apply(entry.clientName()), entry.scriptName()))
                .toList();
    }

    @Override
    public List<ScriptStatusEntry> getStatusForGroup(String groupName) {
        return scope.get().seesGroup(groupName) ? delegate.getStatusForGroup(groupName) : List.of();
    }

    // ── Scoping ─────────────────────────────────────────────────────────────

    /** Why a start of {@code scriptName} on {@code client} is not allowed, if it is not. */
    private Optional<String> refusalToStart(Scope now, String client, String scriptName) {
        Optional<String> account = accountOf.apply(client);
        if (!now.covers(account, scriptName)) {
            return Optional.of(NOT_IN_TARGETS);
        }
        return now.isPausedOn(account) ? Optional.of(PAUSED) : Optional.empty();
    }

    private OpResult startOne(Scope now, String client, String scriptName,
                              BiFunction<String, String, OpResult> start) {
        return refusalToStart(now, client, scriptName)
                .map(reason -> new OpResult(false, client, scriptName, reason))
                .orElseGet(() -> start.apply(client, scriptName));
    }

    private OpResult stopOne(Scope now, String client, String scriptName) {
        return now.covers(accountOf.apply(client), scriptName)
                ? delegate.stopScript(client, scriptName)
                : new OpResult(false, client, scriptName, NOT_IN_TARGETS);
    }

    private ScheduleOpResult scheduleOne(Scope now, String client, String scriptName,
                                         BiFunction<String, String, ScheduleOpResult> schedule) {
        return refusalToStart(now, client, scriptName)
                .map(reason -> refusedSchedule(client, scriptName, reason))
                .orElseGet(() -> schedule.apply(client, scriptName));
    }

    /**
     * A start over a group: passed on whole when the group is covered and no
     * member is paused, else one start per connected member, each checked.
     */
    private List<OpResult> startOnGroup(String groupName, String scriptName, Supplier<List<OpResult>> whole,
                                        BiFunction<String, String, OpResult> start) {
        Scope now = scope.get();
        if (!now.seesGroup(groupName)) {
            return List.of(new OpResult(false, groupName, scriptName, NOT_IN_TARGETS));
        }
        Set<String> members = delegate.getGroupMembers(groupName);
        if (members.stream().noneMatch(member -> now.isPausedOn(accountOf.apply(member)))) {
            return whole.get();
        }
        return members.stream().map(member -> startOne(now, member, scriptName, start)).toList();
    }

    private List<OpResult> stopOnGroup(String groupName, String scriptName, Supplier<List<OpResult>> whole) {
        return scope.get().seesGroup(groupName)
                ? whole.get()
                : List.of(new OpResult(false, groupName, scriptName, NOT_IN_TARGETS));
    }

    /** A start over every client: passed on whole when nothing is withheld, else one per covered live client. */
    private List<OpResult> startOnAll(String scriptName, Supplier<List<OpResult>> whole,
                                      BiFunction<String, String, OpResult> start) {
        Scope now = scope.get();
        if (now.isUnrestricted()) {
            return whole.get();
        }
        return coveredLiveClients(now, scriptName).stream()
                .map(client -> startOne(now, client, scriptName, start))
                .toList();
    }

    private List<ScheduleOpResult> scheduleGroup(String call, String groupName, String scriptName,
                                                 Supplier<List<ScheduleOpResult>> whole,
                                                 BiFunction<String, String, ScheduleOpResult> schedule) {
        return audited(call, on(scriptName, GROUP + groupName), () -> {
            Scope now = scope.get();
            if (!now.seesGroup(groupName)) {
                return List.of(refusedSchedule(groupName, scriptName, NOT_IN_TARGETS));
            }
            Set<String> members = delegate.getGroupMembers(groupName);
            if (members.stream().noneMatch(member -> now.isPausedOn(accountOf.apply(member)))) {
                return whole.get();
            }
            return members.stream().map(member -> scheduleOne(now, member, scriptName, schedule)).toList();
        }, ScopedClientOrchestrator::summaryOfSchedules);
    }

    private List<ScheduleOpResult> scheduleAll(String call, String scriptName,
                                               Supplier<List<ScheduleOpResult>> whole,
                                               BiFunction<String, String, ScheduleOpResult> schedule) {
        return audited(call, on(scriptName, EVERY_CLIENT), () -> {
            Scope now = scope.get();
            if (now.isUnrestricted()) {
                return whole.get();
            }
            return coveredLiveClients(now, scriptName).stream()
                    .map(client -> scheduleOne(now, client, scriptName, schedule))
                    .toList();
        }, ScopedClientOrchestrator::summaryOfSchedules);
    }

    private List<String> coveredLiveClients(Scope now, String scriptName) {
        return delegate.getClientNames().stream()
                .filter(delegate::isClientAlive)
                .filter(client -> now.covers(accountOf.apply(client), scriptName))
                .toList();
    }

    private List<ScheduledScriptEntry> coveredSchedules(List<ScheduledScriptEntry> entries) {
        Scope now = scope.get();
        return entries.stream()
                .filter(entry -> now.covers(accountOf.apply(entry.clientName()), entry.scriptName()))
                .toList();
    }

    private boolean changeGroups(String call, String target, Supplier<Boolean> change) {
        if (!scope.get().isWholeHost()) {
            audit.record(script, call, target, REFUSED + GROUPS_NEED_WHOLE_HOST);
            return false;
        }
        return audited(call, target, change, isChanged -> isChanged ? DONE : NO_CHANGE);
    }

    // ── Audit ───────────────────────────────────────────────────────────────

    private <T> T audited(String call, String target, Supplier<T> action, Function<T, String> summary) {
        T result = action.get();
        audit.record(script, call, target, summary.apply(result));
        return result;
    }

    private static String on(String scriptName, String where) {
        return scriptName + " on " + where;
    }

    private static ScheduleOpResult refusedSchedule(String client, String scriptName, String reason) {
        return new ScheduleOpResult(false, client, scriptName, null, reason);
    }

    private static String summary(OpResult result) {
        return result.success() ? result.message() : failure(result.message());
    }

    private static String summary(ScheduleOpResult result) {
        return result.success() ? result.message() : failure(result.message());
    }

    /** @param result empty when the schedule is not one the script may see */
    private static String summaryOfCancel(Optional<Boolean> result) {
        return result.map(isCancelled -> isCancelled ? "cancelled" : NO_CHANGE)
                .orElse(REFUSED + NOT_IN_TARGETS);
    }

    private static String summaryOfOps(List<OpResult> results) {
        if (results.size() == 1) {
            return summary(results.getFirst());
        }
        return counts(results.stream().filter(OpResult::success).count(), results.size());
    }

    private static String summaryOfSchedules(List<ScheduleOpResult> results) {
        if (results.size() == 1) {
            return summary(results.getFirst());
        }
        return counts(results.stream().filter(ScheduleOpResult::success).count(), results.size());
    }

    private static String failure(String message) {
        boolean isRefusal = NOT_IN_TARGETS.equals(message) || PAUSED.equals(message);
        return (isRefusal ? REFUSED : FAILED) + message;
    }

    private static String counts(long succeeded, int total) {
        if (total == 0) {
            return "no clients";
        }
        return succeeded + " of " + total + " succeeded";
    }
}
