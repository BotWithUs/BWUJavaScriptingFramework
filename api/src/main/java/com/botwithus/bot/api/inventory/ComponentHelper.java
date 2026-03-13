package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.query.ComponentFilter;

import java.util.List;

/**
 * Shared helper methods for interacting with UI components across
 * inventory wrappers (Backpack, Bank, Equipment).
 */
public final class ComponentHelper {

    private ComponentHelper() {}

    /**
     * Computes the interface hash for a component, used as the param3 in game actions.
     *
     * @param comp the component
     * @return the packed hash (interfaceId &lt;&lt; 16 | componentId)
     */
    public static int componentHash(Component comp) {
        return comp.interfaceId() << 16 | comp.componentId();
    }

    /**
     * Queues a component action with the given option index.
     *
     * @param api         the game API
     * @param comp        the target component
     * @param optionIndex the 1-based option index
     * @return always {@code true}
     */
    public static boolean queueComponentAction(GameAPI api, Component comp, int optionIndex) {
        api.queueAction(new GameAction(
                ActionTypes.COMPONENT, optionIndex, comp.subComponentId(), componentHash(comp)));
        return true;
    }

    /**
     * Finds the option index for the given option text and queues the action.
     *
     * @param api    the game API
     * @param comp   the target component
     * @param option the option text to match (case-insensitive)
     * @return {@code true} if the option was found and the action queued
     */
    public static boolean interactComponent(GameAPI api, Component comp, String option) {
        List<String> options = api.getComponentOptions(comp.interfaceId(), comp.componentId());
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equalsIgnoreCase(option)) {
                return queueComponentAction(api, comp, i + 1);
            }
        }
        return false;
    }

    /**
     * Finds a component within an interface that holds the given item.
     *
     * @param api         the game API
     * @param interfaceId the interface to search
     * @param itemId      the item ID to find
     * @return the matching component, or {@code null} if not found
     */
    public static Component findComponentByItem(GameAPI api, int interfaceId, int itemId) {
        List<Component> comps = api.queryComponents(ComponentFilter.builder()
                .interfaceId(interfaceId)
                .itemId(itemId)
                .build());
        return comps.isEmpty() ? null : comps.getFirst();
    }
}
