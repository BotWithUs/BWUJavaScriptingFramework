package com.botwithus.bot.api.model;

/**
 * One node of a flattened interface subtree returned by
 * {@link com.botwithus.bot.api.GameAPI#getInterfaceTree(int, int)} — the raw
 * decode of a single {@code get_interface_tree} entry.
 *
 * <p>{@code parentIndex} references an earlier slot in the same list
 * ({@code -1} for the root), so a consumer can rebuild the tree without a
 * second lookup. The producer walks the live {@code Component*} children
 * vectors, so a child's own interface id rides along in
 * {@code component.ifaceId()} — the tree stays correct across mounted
 * sub-interface boundaries, which per-node
 * {@link com.botwithus.bot.api.GameAPI#getStaticChildren(int, int)} cannot
 * follow.</p>
 *
 * <p>Most scripts never touch this directly — the
 * {@link com.botwithus.bot.api.component.Components} facade wraps it into a
 * navigable {@link com.botwithus.bot.api.component.ComponentTree}.</p>
 *
 * @param component   the decoded component at this node
 * @param parentIndex index of this node's parent in the same list, or
 *                    {@code -1} for the root
 */
public record ComponentTreeNode(Component component, int parentIndex) {}
