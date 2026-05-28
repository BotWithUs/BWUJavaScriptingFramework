package com.botwithus.bot.api.component;

import com.botwithus.bot.api.GameAPI;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fluent, RPC-backed query over an interface subtree. Modeled on
 * {@code WorldMapElements.Query}: the first terminal materializes the subtree
 * in a single {@code get_interface_tree} round-trip, then all filters run
 * client-side over the flattened node list. The fetched tree is cached, so
 * chaining several terminals on one query costs one RPC.
 *
 * <pre>{@code
 * ComponentNode coins = api.components().in(Interfaces.BACKPACK)
 *         .withType(ComponentType.MODEL)
 *         .withItemId(995)
 *         .first();
 * }</pre>
 */
public final class ComponentQuery {

    private final GameAPI api;
    private final int rootInterfaceId;
    private final int rootComponentId;

    private Predicate<ComponentNode> filter = node -> true;
    private int limit = Integer.MAX_VALUE;
    private ComponentTree cachedTree;

    ComponentQuery(GameAPI api, int rootInterfaceId, int rootComponentId) {
        this.api = api;
        this.rootInterfaceId = rootInterfaceId;
        this.rootComponentId = rootComponentId;
    }

    // ---------------- Filters ----------------

    /** Adds a predicate; multiple calls AND together. */
    public ComponentQuery filter(Predicate<ComponentNode> predicate) {
        this.filter = this.filter.and(predicate);
        return this;
    }

    /** Filter by component id within the interface. */
    public ComponentQuery withId(int componentId) {
        return filter(node -> node.componentId() == componentId);
    }

    /** Filter to a single semantic category. */
    public ComponentQuery withType(ComponentType type) {
        return filter(node -> node.type() == type);
    }

    /** Filter to any of the given categories. */
    public ComponentQuery ofType(ComponentType... types) {
        return filter(node -> {
            for (ComponentType candidate : types) {
                if (node.type() == candidate) {
                    return true;
                }
            }
            return false;
        });
    }

    /** Filter by exact label text (case-insensitive). */
    public ComponentQuery withText(String text) {
        return filter(node -> text.equalsIgnoreCase(node.text()));
    }

    /** Filter by label substring (case-insensitive). */
    public ComponentQuery containingText(String needle) {
        String lower = needle.toLowerCase();
        return filter(node -> node.text().toLowerCase().contains(lower));
    }

    /** Filter by label regex (full match against the label). */
    public ComponentQuery textMatching(String regex) {
        Pattern compiled = Pattern.compile(regex);
        return filter(node -> compiled.matcher(node.text()).matches());
    }

    /** Filter by primary sprite id. */
    public ComponentQuery withSpriteId(int spriteId) {
        return filter(node -> node.spriteId() == spriteId);
    }

    /** Filter by displayed item id (ModelComponent). */
    public ComponentQuery withItemId(int itemId) {
        return filter(node -> node.itemId() == itemId);
    }

    /** Keep only visible components (treats unsupported-hidden as visible). */
    public ComponentQuery visible() {
        return filter(node -> !node.isHidden());
    }

    /** Keep only components the producer reported hidden. */
    public ComponentQuery hidden() {
        return filter(ComponentNode::isHidden);
    }

    /** Cap the number of results. */
    public ComponentQuery limit(int max) {
        this.limit = Math.max(0, max);
        return this;
    }

    // ---------------- Terminals ----------------

    /** All matching nodes, in breadth-first tree order. */
    public List<ComponentNode> all() {
        List<ComponentNode> out = new ArrayList<>();
        for (ComponentNode node : materialize().nodes()) {
            if (filter.test(node)) {
                out.add(node);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /** Alias for {@link #all()}. */
    public List<ComponentNode> results() {
        return all();
    }

    /** First matching node in tree order, or {@code null}. */
    public ComponentNode first() {
        for (ComponentNode node : materialize().nodes()) {
            if (filter.test(node)) {
                return node;
            }
        }
        return null;
    }

    /** First matching node as an {@link Optional}. */
    public Optional<ComponentNode> findFirst() {
        return Optional.ofNullable(first());
    }

    public int count() {
        return all().size();
    }

    public boolean exists() {
        return first() != null;
    }

    /** Stream over the materialized result list (filter + limit already applied). */
    public Stream<ComponentNode> stream() {
        return all().stream();
    }

    /** The materialized subtree (one fetch, cached for this query). */
    public ComponentTree tree() {
        return materialize();
    }

    /** Root of the materialized subtree, or {@code null} when it didn't resolve. */
    public ComponentNode root() {
        return materialize().root();
    }

    private ComponentTree materialize() {
        if (cachedTree == null) {
            cachedTree = ComponentTree.fetch(api, rootInterfaceId, rootComponentId);
        }
        return cachedTree;
    }
}
