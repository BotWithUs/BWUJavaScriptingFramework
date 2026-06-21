package com.botwithus.bot.api.component;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.util.Interfaces;

import java.util.ArrayList;
import java.util.List;

/**
 * Rich wrapper around a {@link Component} record plus its position in a
 * materialized {@link ComponentTree}. Mirrors the entity wrappers (e.g.
 * {@code Npc}): cheap field access delegating to the record, in-memory tree
 * navigation, and a thin interaction hook.
 *
 * <p>Obtained via {@link Components#in(int)} / {@link Components#under(int, int)}
 * (attached — full navigation, no extra RPC) or {@link Components#get(int, int)}
 * (detached — {@link #parent()} is {@code null} and {@link #children()} triggers
 * a one-shot subtree fetch).</p>
 */
public final class ComponentNode {

    /** param3 for a plain component action: no inventory sub-slot. */
    private static final int NO_SUB_SLOT = -1;

    /** param1 a dialogue-option selection carries (the engine ignores a menu index here). */
    private static final int DIALOGUE_OPTION = 0;

    /** Bit-position the producer unpacks the drag "from" sub-slot from in param3. */
    private static final int FROM_SUB_SHIFT = 16;
    /** Mask for the drag "to" sub-slot half packed into param3. */
    private static final int TO_SUB_MASK = 0xFFFF;

    private final GameAPI api;
    private final Component data;
    /** Owning tree, or {@code null} for a detached node (from {@link Components#get}). */
    private final ComponentTree tree;
    /** Index within {@link #tree}; unused when detached ({@code tree == null}). */
    private final int index;

    ComponentNode(GameAPI api, Component data, ComponentTree tree, int index) {
        this.api = api;
        this.data = data;
        this.tree = tree;
        this.index = index;
    }

    // ---------------- Identity / fields ----------------

    public int interfaceId()    { return data.ifaceId(); }
    public int componentId()    { return data.compId(); }
    public int subId()          { return data.subId(); }
    /** Stable semantic category (decoded from the wire {@code category} code). */
    public ComponentType type() { return ComponentType.fromCode(data.category()); }
    /** Build-specific raw type byte. Prefer {@link #type()} for queries. */
    public int rawType()        { return data.type(); }
    public String text()        { return data.text(); }
    public int spriteId()       { return data.spriteId(); }
    public int itemId()         { return data.itemId(); }
    public int itemAmount()     { return data.itemAmount(); }
    public boolean isHidden()   { return data.isHidden(); }
    public int x()              { return data.x(); }
    public int y()              { return data.y(); }
    public int width()          { return data.width(); }
    public int height()         { return data.height(); }

    /** The underlying raw record. */
    public Component data()     { return data; }

    // ---------------- Tree navigation ----------------

    /** Parent node, or {@code null} at the root / for a detached node. */
    public ComponentNode parent() {
        return tree == null ? null : tree.parentOf(index);
    }

    /**
     * Direct children. For an attached node this is in-memory; for a detached
     * node (from {@link Components#get}) it triggers one
     * {@code get_interface_tree} fetch rooted here.
     */
    public List<ComponentNode> children() {
        if (tree != null) {
            return tree.childrenOf(index);
        }
        ComponentNode freshRoot = ComponentTree.fetch(api, interfaceId(), componentId()).root();
        return freshRoot == null ? List.of() : freshRoot.children();
    }

    /** All descendants, depth-first. One fetch total for a detached node. */
    public List<ComponentNode> descendants() {
        List<ComponentNode> out = new ArrayList<>();
        collectDescendants(out);
        return out;
    }

    private void collectDescendants(List<ComponentNode> out) {
        for (ComponentNode child : children()) {
            out.add(child);
            child.collectDescendants(out);
        }
    }

    /** True when this node has no parent in its view. */
    public boolean isRoot() {
        return parent() == null;
    }

    // ---------------- Interaction ----------------

    /**
     * Queue a click on this component with the given 1-based right-click option
     * index. Builds a {@link GameAction} with {@link ActionTypes#COMPONENT}, the
     * option in {@code param1}, the packed {@code (iface<<16)|comp} in
     * {@code param2}, and {@code -1} (no sub-slot) in {@code param3} — the same
     * shape inventory slot clicks use, minus the slot. For inventory grids use
     * the {@code Backpack}/{@code Bank} facades, which supply the slot.
     */
    public void interact(int optionIndex) {
        api.queueAction(new GameAction(
                ActionTypes.COMPONENT,
                optionIndex,
                NO_SUB_SLOT,
                Interfaces.componentHash(interfaceId(), componentId())));
    }

    /**
     * Queue selection of this component as a multi-choice <b>dialogue option</b>.
     *
     * <p>Dialogue options are driven by the {@link ActionTypes#DIALOGUE} action
     * (type 30) — the engine's resume / pausebutton path — not the
     * {@link ActionTypes#COMPONENT} action {@link #interact} sends. A COMPONENT
     * click on an option row <em>dispatches but the server ignores it</em> (the
     * dialogue doesn't advance); the DIALOGUE action is what a manual option pick
     * emits. Shape: {@code param1 = 0}, no sub-slot, packed {@code (iface<<16)|comp}
     * in {@code param3}. Verified live against interface 1188.</p>
     */
    public void selectDialogOption() {
        api.queueAction(new GameAction(
                ActionTypes.DIALOGUE,
                DIALOGUE_OPTION,
                NO_SUB_SLOT,
                Interfaces.componentHash(interfaceId(), componentId())));
    }

    /**
     * Queue a drag of this component onto {@code target}, reproducing a manual
     * drag-and-release so the client sends its component-drag packet. Builds a
     * {@link GameAction} with {@link ActionTypes#COMPONENT_DRAG}: this node's
     * {@code (iface<<16)|comp} hash in {@code param1}, the target's in
     * {@code param2}, and the two nodes' {@link #subId() sub-slot ids} packed
     * into {@code param3} as {@code (fromSub<<16)|(toSub&0xFFFF)} — each
     * {@code -1} when the node is a top-level component.
     *
     * <p>Because each node carries its own wire {@code subId}, dragging one
     * inventory slot onto another — two sub-components of the same grid that
     * differ only by sub-slot — works: obtain both slot nodes from the
     * component tree, then {@code from.dragOnto(to)}.</p>
     */
    public void dragOnto(ComponentNode target) {
        api.queueAction(new GameAction(
                ActionTypes.COMPONENT_DRAG,
                Interfaces.componentHash(interfaceId(), componentId()),
                Interfaces.componentHash(target.interfaceId(), target.componentId()),
                (subId() << FROM_SUB_SHIFT) | (target.subId() & TO_SUB_MASK)));
    }

    @Override
    public String toString() {
        return "ComponentNode{" + interfaceId() + ":" + componentId()
                + " " + type() + (text().isEmpty() ? "" : " '" + text() + "'") + "}";
    }
}
