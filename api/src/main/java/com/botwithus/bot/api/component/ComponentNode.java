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
                Interfaces.componentHash(interfaceId(), componentId()),
                NO_SUB_SLOT));
    }

    @Override
    public String toString() {
        return "ComponentNode{" + interfaceId() + ":" + componentId()
                + " " + type() + (text().isEmpty() ? "" : " '" + text() + "'") + "}";
    }
}
