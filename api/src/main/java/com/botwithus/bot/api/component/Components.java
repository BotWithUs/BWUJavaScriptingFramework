package com.botwithus.bot.api.component;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.Component;

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
}
