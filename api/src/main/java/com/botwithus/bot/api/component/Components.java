package com.botwithus.bot.api.component;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.ComponentRef;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;

/**
 * Interface-component query facade. Singleton per {@link GameAPI}; obtain via
 * {@code api.components()}. The coherent entry point for inspecting interface
 * components, parallel to {@code api.npcs()} / {@code api.mapElements()}.
 *
 * <p>RPC-backed and poll-based: a query fetches a live subtree in one
 * {@code get_interface_tree} round-trip (cross-mount aware) and filters it
 * client-side. There is no cache across ticks — re-query for fresh state.</p>
 *
 * <pre>{@code
 * Components ui = api.components();
 * if (ui.isOpen(Interfaces.BACKPACK)) {
 *     ComponentNode item = ui.in(Interfaces.BACKPACK)
 *             .withType(ComponentType.MODEL)
 *             .withItemId(995)
 *             .first();
 *     if (item != null) {
 *         item.interact(1);
 *     }
 * }
 * }</pre>
 */
public final class Components {

    /** Conventional root component id of a loaded interface. */
    private static final int ROOT_COMPONENT_ID = 0;
    /** Index marking a detached node (no owning tree). */
    private static final int DETACHED_INDEX = -1;

    private final GameAPI api;

    public Components(GameAPI api) {
        this.api = api;
    }

    /** Start a query over the whole interface (rooted at its root component). */
    public ComponentQuery in(int interfaceId) {
        return new ComponentQuery(api, interfaceId, ROOT_COMPONENT_ID);
    }

    /** Start a query over the subtree rooted at {@code (interfaceId, componentId)}. */
    public ComponentQuery under(int interfaceId, int componentId) {
        return new ComponentQuery(api, interfaceId, componentId);
    }

    /**
     * Single component by coordinates, or {@code null} when not loaded. Cheap
     * (one {@code get_component}); the returned node is detached — its
     * {@link ComponentNode#children()} triggers a subtree fetch on demand, and
     * {@link ComponentNode#parent()} is {@code null}.
     */
    public ComponentNode get(int interfaceId, int componentId) {
        Component data = api.getComponent(interfaceId, componentId);
        return data == null ? null : new ComponentNode(api, data, null, DETACHED_INDEX);
    }

    /**
     * True when the interface's root component resolves — a proxy for "this
     * interface is currently loaded / open".
     */
    public boolean isOpen(int interfaceId) {
        return api.getComponent(interfaceId, ROOT_COMPONENT_ID) != null;
    }

    /** Convenience: first node in the interface matching {@code predicate}, or {@code null}. */
    public ComponentNode find(int interfaceId, Predicate<ComponentNode> predicate) {
        return in(interfaceId).filter(predicate).first();
    }

    /**
     * Detached node for the component under a screen-space coordinate, or
     * {@code null} when nothing is there. Coordinates are raw screen pixels
     * (the value of Win32 {@code GetCursorPos}); the producer converts to
     * client-window space internally.
     *
     * <p>Returns the deepest visible component whose AABB contains the point,
     * across every open interface. The node is detached — {@code parent()} is
     * {@code null} and {@code children()} fetches the subtree on demand.</p>
     */
    public ComponentNode pickAt(int screenX, int screenY) {
        Component data = api.findComponentAt(screenX, screenY);
        return data == null ? null : new ComponentNode(api, data, null, DETACHED_INDEX);
    }

    // ---------------------------------------------------------------- Gameval names

    /**
     * Query over the whole interface named by its gameval, e.g. {@code "BANK"}.
     * Returns an empty query when the name doesn't resolve.
     *
     * <pre>{@code
     * ComponentNode close = api.components().in("BANK")
     *         .withType(ComponentType.MODEL)
     *         .first();
     * }</pre>
     */
    public ComponentQuery in(String interfaceGameval) {
        OptionalInt id = api.gamevals().interfaceId(interfaceGameval);
        return id.isPresent() ? in(id.getAsInt()) : new ComponentQuery(api, ComponentQuery.UNRESOLVED_INTERFACE, ROOT_COMPONENT_ID);
    }

    /**
     * Query over the subtree rooted at the component named by its gameval, e.g.
     * {@code "BANK__CONTENT"}. Returns an empty query when the name doesn't
     * resolve.
     */
    public ComponentQuery under(String componentGameval) {
        Optional<ComponentRef> ref = api.gamevals().component(componentGameval);
        return ref.map(r -> under(r.interfaceId(), r.componentId()))
                .orElseGet(() -> new ComponentQuery(api, ComponentQuery.UNRESOLVED_INTERFACE, ROOT_COMPONENT_ID));
    }

    /**
     * Single component named by its gameval, e.g. {@code "BANK__BANK_INV_BUTTON"}
     * → interface 517, component 39. {@code null} when the name doesn't resolve
     * or the component isn't loaded — the gameval names every component the
     * cache defines, not just the ones currently on screen.
     *
     * <pre>{@code
     * ComponentNode inv = api.components().get("BANK__BANK_INV_BUTTON");
     * if (inv != null) {
     *     inv.interact(1);
     * }
     * }</pre>
     */
    public ComponentNode get(String componentGameval) {
        return api.gamevals().component(componentGameval)
                .map(r -> get(r.interfaceId(), r.componentId()))
                .orElse(null);
    }

    /** True when the interface named by its gameval is currently loaded. */
    public boolean isOpen(String interfaceGameval) {
        OptionalInt id = api.gamevals().interfaceId(interfaceGameval);
        return id.isPresent() && isOpen(id.getAsInt());
    }

    /**
     * Convenience: first node in the gameval-named interface matching
     * {@code predicate}, or {@code null}.
     */
    public ComponentNode find(String interfaceGameval, Predicate<ComponentNode> predicate) {
        return in(interfaceGameval).filter(predicate).first();
    }
}
