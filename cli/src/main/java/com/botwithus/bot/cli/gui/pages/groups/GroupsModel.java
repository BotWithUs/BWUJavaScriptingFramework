package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.gui.usermode.board.ScriptEntry;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * What the Groups page reads and asks for. The live model reads the host's group
 * store, client registry and connections; the dev preview supplies fixtures.
 *
 * <p>Every method runs on the render thread and returns promptly. Anything that
 * writes the groups file, starts or stops a script happens elsewhere, shows up
 * in a later {@link #snapshot()}, and reports what it did through
 * {@link #notice()}. Asking about a group or member that has gone does nothing.</p>
 */
public interface GroupsModel {

    /** This frame's groups, members and clients. */
    GroupsSnapshot snapshot();

    /**
     * How many groups there are: the sidebar's count. Read every frame the
     * sidebar is drawn, page shown or not, so it must be cheap.
     */
    int groupCount();

    /** The installed scripts the Start script dialog offers, from the last completed load. */
    List<ScriptEntry> catalog();

    /** What the last action did, until it is dismissed or replaced. */
    Optional<Notice> notice();

    void dismissNotice();

    /** The group the last {@link #createGroup} made, once, so the page can show it. */
    Optional<GroupId> takeCreated();

    /** Creates a group called {@code name} and adds {@code members} to it. */
    void createGroup(String name, List<ClientKey> members);

    void rename(GroupId id, String name);

    /** Deletes the group. Its clients and their scripts are untouched. */
    void delete(GroupId id);

    /** Adds clients; any that cannot join are reported with the reason. */
    void addMembers(GroupId id, List<ClientKey> keys);

    /**
     * Removes members by their row keys ({@link MemberRow#key()}): account
     * members and unresolved ones alike. Their scripts keep running.
     */
    void removeMembers(GroupId id, Collection<String> rowKeys);

    /** Starts {@code script} on the group as {@link StartPlan} lays out, queuing it for members not connected. */
    void startScript(GroupId id, String script, BusyChoice choice);

    /**
     * Stops every script running on the group's members and cancels every start
     * queued for them; each script stays listed as stopped, so it can be run
     * again from its row. A manager assigned to the group is paused first, so it
     * does not start again what was just stopped.
     */
    void stopAll(GroupId id);

    /** Stops the running scripts, and cancels the queued starts, of the members on accounts {@code uuids}. */
    void stopMembers(Collection<String> uuids);

    /** Runs {@code script} again on the connected client on account {@code uuid}. */
    void run(String uuid, String script);

    /** Starts the crashed {@code script} again on the connected client on account {@code uuid}. */
    void restart(String uuid, String script);

    // ── The group's manager ────────────────────────────────────────────────

    /** The group's manager as its slot shows it; empty when it has none. */
    Optional<ManagerInfo> manager(GroupId id);

    /** The management scripts loaded now, by name: what the Assign manager dialog offers. */
    List<ManagerChoice> managers();

    /**
     * Makes {@code script} the group's manager, in place of any other, and
     * starts it when {@code isStartNow}. A group has one manager.
     */
    void assignManager(GroupId id, String script, boolean isStartNow);

    /** Lets a paused or stopped manager manage the group again: resumes it, and starts it if it is not running. */
    void startManager(GroupId id);

    /** Opens the shared inspector on the manager's settings for this group. */
    void openManagerSettings(GroupId id);

    /**
     * The robot link under a member whose {@code script} a management script
     * targets directly, not only through the group; empty when none does.
     */
    Optional<MemberManagement> memberManagement(GroupId id, String uuid, String script);

    /** Opens the Management page on {@code script}. */
    void openManagement(String script);
}
