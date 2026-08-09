package com.botwithus.bot.api.component;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.ComponentRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /**
     * Root interface id standing for "the gameval naming this root did not
     * resolve". Real interface ids are non-negative, so this can never collide;
     * {@link #materialize()} short-circuits on it rather than spending a
     * round-trip on a lookup that cannot succeed.
     */
    static final int UNRESOLVED_INTERFACE = -1;

    private static final Logger log = LoggerFactory.getLogger(ComponentQuery.class);

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

    /**
     * A query whose root gameval did not resolve: yields nothing and costs no
     * round-trip. Keeps the {@link #UNRESOLVED_INTERFACE} sentinel from leaking
     * into {@link Components}.
     */
    static ComponentQuery unresolved(GameAPI api) {
        return new ComponentQuery(api, UNRESOLVED_INTERFACE, 0);
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

    /**
     * Filter by gameval symbolic name, e.g. {@code "BANK__BANK_INV_BUTTON"}.
     * Pass several to match any of them.
     *
     * <p>Matches on the <em>whole</em> {@code (interfaceId, componentId)} pair a
     * gameval encodes, not just the component half — a materialized tree is
     * cross-mount aware and can carry nodes from more than one interface, so
     * matching the component id alone would collide across mounts.</p>
     *
     * <p>Names are resolved once when the filter is added. A name that does not
     * resolve narrows the query to nothing and logs a warning — a stale name
     * must not silently widen the result set.</p>
     *
     * <pre>{@code
     * ComponentNode inv = api.components().in("BANK")
     *         .withGameval("BANK__BANK_INV_BUTTON")
     *         .visible()
     *         .first();
     * }</pre>
     */
    public ComponentQuery withGameval(String... gamevals) {
        if (gamevals.length == 0) {
            log.warn("withGameval() was given no component names; this query will match nothing");
            return filter(node -> false);
        }
        List<ComponentRef> refs = new ArrayList<>(gamevals.length);
        for (String gameval : gamevals) {
            Optional<ComponentRef> ref = api.gamevals().component(gameval);
            if (ref.isPresent()) {
                refs.add(ref.get());
            } else {
                // The index warns once per distinct unknown name; debug here
                // keeps a per-tick query from doubling that.
                log.debug("gameval component '{}' did not resolve; excluded from this query",
                        gameval);
            }
        }
        if (refs.isEmpty()) {
            return filter(node -> false);
        }
        return filter(node -> {
            for (ComponentRef ref : refs) {
                if (node.interfaceId() == ref.interfaceId()
                        && node.componentId() == ref.componentId()) {
                    return true;
                }
            }
            return false;
        });
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
            cachedTree = rootInterfaceId == UNRESOLVED_INTERFACE
                    ? ComponentTree.empty(api)
                    : ComponentTree.fetch(api, rootInterfaceId, rootComponentId);
        }
        return cachedTree;
    }
}
