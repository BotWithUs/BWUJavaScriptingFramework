package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.ComponentPosition;
import com.botwithus.bot.api.model.ComponentTypeInfo;
import com.botwithus.bot.api.model.InventoryItem;
import com.botwithus.bot.api.model.OpenInterface;
import com.botwithus.bot.api.query.ComponentFilter;

import java.util.List;

/**
 * Interface component queries and interaction.
 *
 * @see com.botwithus.bot.api.GameAPI
 */
public interface ComponentAPI {

    /**
     * Queries interface components matching the given filter.
     *
     * @param filter the component filter criteria
     * @return a list of matching components
     * @see ComponentFilter
     */
    List<Component> queryComponents(ComponentFilter filter);

    /**
     * Checks whether a specific component is valid and loaded.
     *
     * @param interfaceId    the parent interface ID
     * @param componentId    the component ID within the interface
     * @param subComponentId the sub-component ID, or {@code -1} for none
     * @return {@code true} if the component is valid
     */
    boolean isComponentValid(int interfaceId, int componentId, int subComponentId);

    /**
     * Returns the text content of a component.
     *
     * @param interfaceId the parent interface ID
     * @param componentId the component ID
     * @return the component text, or {@code null} if none
     */
    String getComponentText(int interfaceId, int componentId);

    /**
     * Returns the item displayed by a component.
     *
     * @param interfaceId    the parent interface ID
     * @param componentId    the component ID
     * @param subComponentId the sub-component ID
     * @return the inventory item shown by the component
     */
    InventoryItem getComponentItem(int interfaceId, int componentId, int subComponentId);

    /**
     * Returns the screen position and dimensions of a component.
     *
     * @param interfaceId the parent interface ID
     * @param componentId the component ID
     * @return the component position
     */
    ComponentPosition getComponentPosition(int interfaceId, int componentId);

    /**
     * Returns the right-click menu options available on a component.
     *
     * @param interfaceId the parent interface ID
     * @param componentId the component ID
     * @return a list of option strings
     */
    List<String> getComponentOptions(int interfaceId, int componentId);

    /**
     * Returns the sprite ID displayed by a component.
     *
     * @param interfaceId the parent interface ID
     * @param componentId the component ID
     * @return the sprite ID
     */
    int getComponentSpriteId(int interfaceId, int componentId);

    /**
     * Returns the type information of a component.
     *
     * @param interfaceId the parent interface ID
     * @param componentId the component ID
     * @return the component type info
     */
    ComponentTypeInfo getComponentType(int interfaceId, int componentId);

    /**
     * Returns the child components of a parent component.
     *
     * @param interfaceId the parent interface ID
     * @param componentId the parent component ID
     * @return a list of child components
     */
    List<Component> getComponentChildren(int interfaceId, int componentId);

    /**
     * Returns the packed component hash for addressing a specific sub-component.
     *
     * @param interfaceId    the parent interface ID
     * @param componentId    the component ID
     * @param subComponentId the sub-component ID
     * @return the packed component hash
     */
    int getComponentByHash(int interfaceId, int componentId, int subComponentId);

    /**
     * Returns all currently open interfaces.
     *
     * @return a list of open interfaces
     */
    List<OpenInterface> getOpenInterfaces();

    /**
     * Checks whether an interface is currently open.
     *
     * @param interfaceId the interface ID to check
     * @return {@code true} if the interface is open
     */
    boolean isInterfaceOpen(int interfaceId);
}
