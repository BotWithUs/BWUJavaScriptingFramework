package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;

import imgui.type.ImString;

import java.util.List;
import java.util.Optional;

/**
 * What the Groups page remembers between frames: the group shown, the ticked
 * members, and which inline confirm or rename is open. Changing the group shown
 * clears the rest, so nothing carries over to a group it was not meant for.
 *
 * <p>Render thread only.</p>
 */
final class GroupsPageState {

    /** The inline question the header is asking instead of showing its buttons. */
    enum Confirm { NONE, STOP_ALL, DELETE }

    private static final int NAME_CAPACITY = 128;

    private final TickedKeys<GroupId> ticks = new TickedKeys<>();
    private final ImString renameBuffer = new ImString(NAME_CAPACITY);
    private GroupId selected;
    private Confirm confirm = Confirm.NONE;
    private boolean isRenaming;
    private boolean shouldFocusRename;

    /**
     * The group to show: the one selected, if it still exists, else the first
     * group; empty when there are none.
     */
    Optional<ClientGroup> resolve(List<ClientGroup> groups) {
        Optional<ClientGroup> current = groups.stream().filter(g -> g.id().equals(selected)).findFirst();
        if (current.isPresent()) {
            return current;
        }
        Optional<ClientGroup> first = groups.stream().findFirst();
        first.ifPresentOrElse(group -> select(group.id()), () -> selected = null);
        return first;
    }

    Optional<GroupId> selected() {
        return Optional.ofNullable(selected);
    }

    /** Shows {@code id}, closing any confirm or rename and clearing the ticks. */
    void select(GroupId id) {
        if (id.equals(selected)) {
            return;
        }
        selected = id;
        confirm = Confirm.NONE;
        isRenaming = false;
        ticks.clear();
    }

    TickedKeys<GroupId> ticks() {
        return ticks;
    }

    Confirm confirm() {
        return confirm;
    }

    void ask(Confirm question) {
        confirm = question;
        isRenaming = false;
    }

    boolean isRenaming() {
        return isRenaming;
    }

    /** Opens the inline rename field on {@code current}, with its focus on the next frame. */
    void startRename(String current) {
        renameBuffer.set(current);
        isRenaming = true;
        shouldFocusRename = true;
        confirm = Confirm.NONE;
    }

    void stopRename() {
        isRenaming = false;
    }

    ImString renameBuffer() {
        return renameBuffer;
    }

    /** Whether the rename field should take the keyboard this frame; true once per rename. */
    boolean takeRenameFocus() {
        boolean isNow = shouldFocusRename;
        shouldFocusRename = false;
        return isNow;
    }
}
