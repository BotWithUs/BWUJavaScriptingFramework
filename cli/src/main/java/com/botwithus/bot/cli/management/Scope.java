package com.botwithus.bot.cli.management;

import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.ManagerSlot;

import java.util.List;
import java.util.Optional;

/**
 * What one management script's targets cover, as they stand at one moment.
 * Immutable: {@link ManagementTargets#scopeOf} builds a new one for each
 * orchestrator call, so a change the user makes applies to the next call.
 *
 * <p>A client is named by its account UUID here. A client with none (a
 * development client, or one not yet identified) is covered only by the
 * whole host.</p>
 *
 * @param isWholeHost   whether the script manages every client
 * @param groups        the groups whose manager slot holds the script, as they are now
 * @param clientScripts the single client scripts it manages
 */
public record Scope(boolean isWholeHost, List<ClientGroup> groups, List<Target.ClientScript> clientScripts) {

    /** Covers nothing: the script has not been applied to anything. */
    public static final Scope NOT_APPLIED = new Scope(false, List.of(), List.of());

    public Scope {
        groups = List.copyOf(groups);
        clientScripts = List.copyOf(clientScripts);
    }

    /** Whether the targets cover nothing at all. */
    public boolean isNotApplied() {
        return !isWholeHost && groups.isEmpty() && clientScripts.isEmpty();
    }

    /**
     * Whether nothing is withheld from the script: it manages the whole host
     * and is paused on no group.
     */
    public boolean isUnrestricted() {
        return isWholeHost && groups.stream().noneMatch(Scope::isPaused);
    }

    /** Whether the script may see the client on {@code account}, for any of its scripts. */
    public boolean coversClient(Optional<String> account) {
        if (isWholeHost) {
            return true;
        }
        return account.filter(uuid -> isGroupMember(uuid)
                || clientScripts.stream().anyMatch(target -> target.accountUuid().equals(uuid))).isPresent();
    }

    /** Whether the script may see, and act on, {@code script} on the client on {@code account}. */
    public boolean covers(Optional<String> account, String script) {
        if (isWholeHost) {
            return true;
        }
        return account.filter(uuid -> isGroupMember(uuid)
                || clientScripts.stream().anyMatch(target -> target.accountUuid().equals(uuid)
                        && target.scriptName().equals(script))).isPresent();
    }

    /**
     * Whether the script is paused on a group the client on {@code account} is
     * in. A paused script may still see and stop what is running there, but it
     * must not start anything: the user stopped those clients on purpose.
     */
    public boolean isPausedOn(Optional<String> account) {
        return account.filter(uuid -> groups.stream()
                .anyMatch(group -> isPaused(group) && group.contains(uuid))).isPresent();
    }

    /** Whether the script may see the group called {@code name}. */
    public boolean seesGroup(String name) {
        return isWholeHost || groups.stream().anyMatch(group -> group.name().equals(name));
    }

    private boolean isGroupMember(String uuid) {
        return groups.stream().anyMatch(group -> group.contains(uuid));
    }

    private static boolean isPaused(ClientGroup group) {
        return group.manager().map(ManagerSlot::shouldRun).map(shouldRun -> !shouldRun).orElse(false);
    }
}
