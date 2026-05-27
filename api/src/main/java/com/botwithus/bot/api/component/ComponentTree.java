package com.botwithus.bot.api.component;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.ComponentTreeNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable, materialized interface subtree fetched in a single
 * {@code get_interface_tree} round-trip. Holds the flattened node list plus
 * parent/child linkage so navigation ({@link ComponentNode#parent()},
 * {@link ComponentNode#children()}, {@link ComponentNode#descendants()}) is
 * pure in-memory work — no further RPC.
 *
 * <p>The tree is a snapshot of one tick; don't hold it across ticks. Build a
 * fresh one (via {@link Components#in(int)} / {@link Components#under(int, int)})
 * when you need current state.</p>
 */
public final class ComponentTree {

    private final List<ComponentNode> nodes;
    private final List<List<ComponentNode>> childrenByIndex;
    private final int[] parentByIndex;

    private ComponentTree(GameAPI api, List<ComponentTreeNode> raw) {
        int n = raw.size();
        this.nodes = new ArrayList<>(n);
        this.childrenByIndex = new ArrayList<>(n);
        this.parentByIndex = new int[n];
        for (int i = 0; i < n; ++i) {
            this.childrenByIndex.add(new ArrayList<>());
        }
        for (int i = 0; i < n; ++i) {
            ComponentTreeNode rawNode = raw.get(i);
            nodes.add(new ComponentNode(api, rawNode.component(), this, i));
            parentByIndex[i] = rawNode.parentIndex();
        }
        for (int i = 0; i < n; ++i) {
            int parent = parentByIndex[i];
            if (parent >= 0 && parent < n) {
                childrenByIndex.get(parent).add(nodes.get(i));
            }
        }
    }

    /** Fetch + materialize the subtree rooted at {@code (interfaceId, componentId)}. */
    public static ComponentTree fetch(GameAPI api, int interfaceId, int componentId) {
        return new ComponentTree(api, api.getInterfaceTree(interfaceId, componentId));
    }

    /** The root node, or {@code null} when the subtree didn't resolve (empty tree). */
    public ComponentNode root() {
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    /** All nodes in breadth-first order. Unmodifiable. */
    public List<ComponentNode> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    /** First node matching {@code (interfaceId, componentId)}, or {@code null}. */
    public ComponentNode node(int interfaceId, int componentId) {
        for (ComponentNode node : nodes) {
            if (node.interfaceId() == interfaceId && node.componentId() == componentId) {
                return node;
            }
        }
        return null;
    }

    // ---------------- Package-private linkage used by ComponentNode ----------------

    List<ComponentNode> childrenOf(int index) {
        return Collections.unmodifiableList(childrenByIndex.get(index));
    }

    ComponentNode parentOf(int index) {
        int parent = parentByIndex[index];
        return (parent >= 0 && parent < nodes.size()) ? nodes.get(parent) : null;
    }
}
