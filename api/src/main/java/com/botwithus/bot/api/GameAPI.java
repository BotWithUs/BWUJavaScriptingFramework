package com.botwithus.bot.api;

import com.botwithus.bot.api.model.*;
import com.botwithus.bot.api.query.ComponentFilter;
import com.botwithus.bot.api.query.EntityFilter;
import com.botwithus.bot.api.query.InventoryFilter;

import java.util.List;

/**
 * Primary interface for interacting with the game client over the pipe RPC.
 *
 * <p>Provides methods for querying game state, entities, inventories, components,
 * and variables, as well as queuing actions and executing client scripts.</p>
 *
 * <p>Obtain an instance through {@link ScriptContext#getGameAPI()}.</p>
 *
 * @see ScriptContext
 * @see BotScript
 */
public interface GameAPI {

    // ============================== System ==============================

    /**
     * Tests connectivity to the game client.
     *
     * @return {@code true} if the client responded successfully
     */
    boolean ping();

    /**
     * Lists all RPC method names supported by the game client.
     *
     * @return a list of available method names
     */
    List<String> listMethods();

    /**
     * Subscribes to a named game event so it is forwarded over the pipe.
     *
     * @param event the event name to subscribe to
     */
    void subscribe(String event);

    /**
     * Unsubscribes from a previously subscribed game event.
     *
     * @param event the event name to unsubscribe from
     */
    void unsubscribe(String event);

    /**
     * Returns the number of clients currently connected to the pipe server.
     *
     * @return the connected client count
     */
    int getClientCount();

    /**
     * Lists all available event names that can be subscribed to.
     *
     * @return a list of event names
     */
    List<String> listEvents();

    /**
     * Returns the calling client's current event subscriptions.
     *
     * @return a list of subscribed event names
     */
    List<String> getSubscriptions();

    // ============================== Actions ==============================

    /**
     * Queues a single game action for execution on the next game tick.
     *
     * @param action the action to queue
     */
    void queueAction(GameAction action);

    /**
     * Queues multiple game actions for execution.
     *
     * @param actions the actions to queue
     * @return the number of actions successfully queued
     */
    int queueActions(List<GameAction> actions);

    /**
     * Returns the number of actions currently pending in the queue.
     *
     * @return the queue size
     */
    int getActionQueueSize();

    /**
     * Clears all pending actions from the queue.
     */
    void clearActionQueue();

    /**
     * Returns a history of recently executed actions.
     *
     * @param maxResults      maximum number of entries to return
     * @param actionIdFilter  filter by action ID, or {@code -1} for all
     * @return a list of historical action entries
     */
    List<ActionEntry> getActionHistory(int maxResults, int actionIdFilter);

    /**
     * Returns the game-cycle timestamp of the last executed action.
     *
     * @return the timestamp in milliseconds
     */
    long getLastActionTime();

    /**
     * Sets a behavior modifier value that adjusts action execution.
     *
     * @param modId the modifier identifier
     * @param value the modifier value
     */
    void setBehaviorMod(int modId, float value);

    /**
     * Clears a previously set behavior modifier.
     *
     * @param modId the modifier identifier to clear
     */
    void clearBehaviorMod(int modId);

    /**
     * Returns the current value of a behavior modifier.
     *
     * @param modId the modifier identifier
     * @return the modifier value, or {@code 0.0f} if not set
     */
    float getBehaviorMod(int modId);

    /**
     * Checks whether action queuing is currently blocked.
     *
     * @return {@code true} if actions are blocked
     */
    boolean areActionsBlocked();

    /**
     * Enables or disables action blocking.
     *
     * @param blocked {@code true} to block actions, {@code false} to unblock
     */
    void setActionsBlocked(boolean blocked);

    // ============================== Entity Queries ==============================

    /**
     * Queries entities (NPCs, players, objects) matching the given filter.
     *
     * @param filter the entity filter criteria
     * @return a list of matching entities
     * @see EntityFilter
     */
    List<Entity> queryEntities(EntityFilter filter);

    /**
     * Returns extended information about an entity.
     *
     * @param handle the entity handle
     * @return the entity info, or {@code null} if the handle is invalid
     */
    EntityInfo getEntityInfo(int handle);

    /**
     * Returns the display name of an entity.
     *
     * @param handle the entity handle
     * @return the entity name, or {@code null} if the handle is invalid
     */
    String getEntityName(int handle);

    /**
     * Returns the health state of an entity.
     *
     * @param handle the entity handle
     * @return the entity health data
     */
    EntityHealth getEntityHealth(int handle);

    /**
     * Returns the tile position of an entity.
     *
     * @param handle the entity handle
     * @return the entity position
     */
    EntityPosition getEntityPosition(int handle);

    /**
     * Checks whether an entity handle still refers to a valid in-game entity.
     *
     * @param handle the entity handle
     * @return {@code true} if the entity is still valid
     */
    boolean isEntityValid(int handle);

    /**
     * Returns the active hitmarks (damage splats) on an entity.
     *
     * @param handle the entity handle
     * @return a list of hitmarks
     */
    List<Hitmark> getEntityHitmarks(int handle);

    /**
     * Returns the current animation ID of an entity.
     *
     * @param handle the entity handle
     * @return the animation ID, or {@code -1} if idle
     */
    int getEntityAnimation(int handle);

    /**
     * Returns any overhead text currently displayed on an entity.
     *
     * @param handle the entity handle
     * @return the overhead text, or {@code null} if none
     */
    String getEntityOverheadText(int handle);

    /**
     * Returns the duration of an animation in game ticks.
     *
     * @param animationId the animation ID
     * @return the animation length in ticks
     */
    int getAnimationLength(int animationId);

    // ============================== Ground Items ==============================

    /**
     * Queries ground item stacks matching the given filter.
     *
     * @param filter the entity filter criteria for ground items
     * @return a list of matching ground item stacks
     */
    List<GroundItemStack> queryGroundItems(EntityFilter filter);

    /**
     * Returns the individual items within an object stack.
     *
     * @param handle the object stack handle
     * @return a list of ground items in the stack
     */
    List<GroundItem> getObjStackItems(int handle);

    /**
     * Queries object stack entities matching the given filter.
     *
     * @param filter the entity filter criteria
     * @return a list of matching object stack entities
     */
    List<Entity> queryObjStacks(EntityFilter filter);

    // ============================== Projectiles ==============================

    /**
     * Queries active projectiles in the game world.
     *
     * @param projectileId the projectile type ID to filter by, or {@code -1} for all
     * @param plane        the plane (height level) to search
     * @param maxResults   maximum number of results to return
     * @return a list of matching projectiles
     */
    List<Projectile> queryProjectiles(int projectileId, int plane, int maxResults);

    // ============================== Spot Animations ==============================

    /**
     * Queries active spot animations (graphics) in the game world.
     *
     * @param animId     the animation ID to filter by, or {@code -1} for all
     * @param plane      the plane (height level) to search
     * @param maxResults maximum number of results to return
     * @return a list of matching spot animations
     */
    List<SpotAnim> querySpotAnims(int animId, int plane, int maxResults);

    // ============================== Hint Arrows ==============================

    /**
     * Queries active hint arrows displayed on the game screen.
     *
     * @param maxResults maximum number of results to return
     * @return a list of active hint arrows
     */
    List<HintArrow> queryHintArrows(int maxResults);

    // ============================== Worlds ==============================

    /**
     * Queries the list of available game worlds.
     *
     * @param includeActivity {@code true} to include activity descriptions
     * @return a list of worlds
     */
    List<World> queryWorlds(boolean includeActivity);

    /**
     * Returns information about the world the player is currently logged into.
     *
     * @return the current world
     */
    World getCurrentWorld();

    /**
     * Computes a name hash from a display name string.
     *
     * @param name the display name
     * @return the computed name hash
     */
    int computeNameHash(String name);

    /**
     * Refreshes the internal query context used for entity lookups.
     * Call this before performing a batch of queries for consistent results.
     */
    void updateQueryContext();

    /**
     * Invalidates the cached query context, forcing a refresh on the next query.
     */
    void invalidateQueryContext();

    // ============================== Components & Interfaces ==============================

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

    // ============================== Game Variables ==============================

    /**
     * Returns the value of a player variable (varp).
     *
     * @param varId the variable ID
     * @return the variable value
     */
    int getVarp(int varId);

    /**
     * Returns the value of a variable bit (varbit).
     *
     * @param varbitId the varbit ID
     * @return the varbit value
     */
    int getVarbit(int varbitId);

    /**
     * Returns the value of an integer client variable (varc).
     *
     * @param varcId the varc ID
     * @return the varc value
     */
    int getVarcInt(int varcId);

    /**
     * Returns the value of a string client variable (varc).
     *
     * @param varcId the varc ID
     * @return the varc string value
     */
    String getVarcString(int varcId);

    /**
     * Batch-queries multiple varbit values at once.
     *
     * @param varbitIds the varbit IDs to query
     * @return a list of varbit values
     */
    List<VarbitValue> queryVarbits(List<Integer> varbitIds);

    // ============================== Script Execution ==============================

    /**
     * Obtains a handle to a client script for repeated execution.
     * The handle must be released with {@link #destroyScriptHandle(long)} when no longer needed.
     *
     * @param scriptId the client script ID
     * @return the script handle
     * @see #executeScript(long, int[], String[], String[])
     * @see #destroyScriptHandle(long)
     */
    long getScriptHandle(int scriptId);

    /**
     * Executes a client script with the given arguments.
     *
     * @param handle     the script handle obtained from {@link #getScriptHandle(int)}
     * @param intArgs    integer arguments to pass to the script
     * @param stringArgs string arguments to pass to the script
     * @param returns    expected return type descriptors
     * @return the script execution result
     */
    ScriptResult executeScript(long handle, int[] intArgs, String[] stringArgs, String[] returns);

    /**
     * Releases a script handle obtained from {@link #getScriptHandle(int)}.
     *
     * @param handle the script handle to release
     */
    void destroyScriptHandle(long handle);

    /**
     * Fires a key input trigger on an interface component.
     *
     * @param interfaceId the interface ID
     * @param componentId the component ID
     * @param input       the input string to send
     */
    void fireKeyTrigger(int interfaceId, int componentId, String input);

    // ============================== Game State ==============================

    /**
     * Returns information about the local player.
     *
     * @return the local player data
     */
    LocalPlayer getLocalPlayer();

    /**
     * Returns account information for the current client session.
     *
     * @return the account info
     */
    AccountInfo getAccountInfo();

    /**
     * Returns the current game cycle (tick count).
     *
     * @return the game cycle number
     */
    int getGameCycle();

    /**
     * Returns the current login state.
     *
     * @return the login state
     */
    LoginState getLoginState();

    /**
     * Returns the entries currently visible in the right-click mini menu.
     *
     * @return a list of mini menu entries
     */
    List<MiniMenuEntry> getMiniMenu();

    /**
     * Returns all active Grand Exchange offers.
     *
     * @return a list of Grand Exchange offers
     */
    List<GrandExchangeOffer> getGrandExchangeOffers();

    /**
     * Converts a world tile coordinate to screen coordinates.
     *
     * @param tileX the tile X coordinate
     * @param tileY the tile Y coordinate
     * @return the screen position
     */
    ScreenPosition getWorldToScreen(int tileX, int tileY);

    /**
     * Batch-converts multiple world tile coordinates to screen coordinates.
     *
     * @param tiles a list of {@code [tileX, tileY]} coordinate pairs
     * @return a list of screen positions
     */
    List<ScreenPosition> batchWorldToScreen(List<int[]> tiles);

    /**
     * Returns viewport and camera information.
     *
     * @return the viewport info
     */
    ViewportInfo getViewportInfo();

    /**
     * Batch-converts entity handles to screen positions.
     *
     * @param handles a list of entity handles
     * @return a list of entity screen positions
     */
    List<EntityScreenPosition> getEntityScreenPositions(List<Integer> handles);

    /**
     * Returns the game window dimensions and client area.
     *
     * @return the game window rect
     */
    GameWindowRect getGameWindowRect();

    /**
     * Reads a file from the game cache.
     *
     * @param indexId   the cache index ID
     * @param archiveId the archive ID within the index
     * @param fileId    the file ID within the archive
     * @return the cache file data
     */
    CacheFile getCacheFile(int indexId, int archiveId, int fileId);

    /**
     * Returns the number of files in a cache archive.
     *
     * @param indexId   the cache index ID
     * @param archiveId the archive ID
     * @param shift     bit-shift for group calculation
     * @return the file count
     */
    int getCacheFileCount(int indexId, int archiveId, int shift);

    /**
     * Initiates a world hop to the specified world.
     *
     * @param worldId the target world ID
     */
    void setWorld(int worldId);

    /**
     * Requests a login state transition.
     *
     * @param oldState the expected current state
     * @param newState the desired new state
     */
    void changeLoginState(int oldState, int newState);

    /**
     * Triggers a login from the login screen to lobby by executing the lobby
     * login script. Only works when the client is on the login screen (state 10).
     *
     * @throws RuntimeException if the client is not on the login screen,
     *         a login is already in progress, or the account is unavailable
     */
    void loginToLobby();

    /**
     * Schedules a break (pause) for the specified duration.
     *
     * @param durationMs the break duration in milliseconds
     */
    void scheduleBreak(int durationMs);

    /**
     * Interrupts any currently active break.
     */
    void interruptBreak();

    /**
     * Retrieves the navigation archive from the game cache.
     *
     * @return the navigation archive cache file
     */
    CacheFile getNavigationArchive();

    /**
     * Checks whether auto-login is enabled.
     *
     * @return {@code true} if auto-login is enabled
     */
    boolean getAutoLogin();

    /**
     * Enables or disables auto-login.
     *
     * @param enabled {@code true} to enable, {@code false} to disable
     */
    void setAutoLogin(boolean enabled);

    /**
     * Captures a screenshot of the game framebuffer as a PNG image.
     * The capture runs on the OpenGL thread before overlay rendering
     * and returns a 1280x720 PNG.
     *
     * @return the screenshot as a cache file containing PNG data
     */
    CacheFile takeScreenshot();

    // ============================== Streaming ==============================

    /**
     * Starts continuous JPEG frame streaming over a dedicated one-way named pipe.
     * The server creates a separate outbound-only pipe for frame data so the main
     * RPC pipe remains unaffected. Connect to the returned pipe name to receive frames.
     *
     * <p>Each frame on the stream pipe is sent as:
     * {@code [4 bytes: little-endian uint32 JPEG size] [N bytes: raw JPEG data]}</p>
     *
     * @param frameSkip capture every Nth frame (1 = every frame), default 2
     * @param quality   JPEG quality 1-100, default 60
     * @param width     output width in pixels (clamped 160-1920), default 960
     * @param height    output height in pixels (clamped 90-1080), default 540
     * @return stream info containing the pipe name and negotiated parameters
     */
    StreamInfo startStream(int frameSkip, int quality, int width, int height);

    /**
     * Stops the active frame stream and closes the stream pipe.
     */
    void stopStream();

    // ============================== Humanization ==============================

    /**
     * Checks whether input humanization is enabled. When enabled, mouse path
     * generation and the fatigue/risk model are active.
     *
     * @return {@code true} if humanization is enabled
     */
    boolean getHumanizationEnabled();

    /**
     * Enables or disables input humanization. When disabled, mouse path generation
     * is skipped (clicks are sent directly) and the fatigue/risk model will not
     * trigger automatic break recommendations.
     *
     * @param enabled {@code true} to enable, {@code false} to disable
     */
    void setHumanizationEnabled(boolean enabled);

    /**
     * Returns the current humanizer personality profile and live session statistics.
     * <p>
     * Personality values are stable per-user traits that define movement characteristics.
     * Session stats reflect the current fatigue/risk state. Use these to adapt timing
     * delays, click precision, and break scheduling.
     *
     * @return the personality profile, or {@code null} if the humanizer is not initialized
     */
    Personality getPersonality();

    // ============================== Inventory & Items ==============================

    /**
     * Queries all available inventories (backpack, bank, equipment, etc.).
     *
     * @return a list of inventory info records
     */
    List<InventoryInfo> queryInventories();

    /**
     * Queries inventory items matching the given filter.
     *
     * @param filter the inventory filter criteria
     * @return a list of matching inventory items
     * @see InventoryFilter
     */
    List<InventoryItem> queryInventoryItems(InventoryFilter filter);

    /**
     * Returns the item in a specific inventory slot.
     *
     * @param inventoryId the inventory ID
     * @param slot        the slot index
     * @return the inventory item, or an empty item if the slot is unoccupied
     */
    InventoryItem getInventoryItem(int inventoryId, int slot);

    /**
     * Returns the custom variables attached to an item in an inventory slot.
     *
     * @param inventoryId the inventory ID
     * @param slot        the slot index
     * @return a list of item variables
     */
    List<ItemVar> getItemVars(int inventoryId, int slot);

    /**
     * Returns a specific variable value for an item in an inventory slot.
     *
     * @param inventoryId the inventory ID
     * @param slot        the slot index
     * @param varId       the variable ID
     * @return the variable value
     */
    int getItemVarValue(int inventoryId, int slot, int varId);

    /**
     * Checks whether an inventory slot contains a valid item.
     *
     * @param inventoryId the inventory ID
     * @param slot        the slot index
     * @return {@code true} if the slot contains a valid item
     */
    boolean isInventoryItemValid(int inventoryId, int slot);

    // ============================== Player Stats ==============================

    /**
     * Returns all player skill stats.
     *
     * @return a list of player stats for every skill
     */
    List<PlayerStat> getPlayerStats();

    /**
     * Returns the stat for a specific skill.
     *
     * @param skillId the skill ID (0-based)
     * @return the player stat for the skill
     */
    PlayerStat getPlayerStat(int skillId);

    /**
     * Returns the total number of skills.
     *
     * @return the skill count
     */
    int getPlayerStatCount();

    // ============================== Chat ==============================

    /**
     * Queries the chat message history.
     *
     * @param messageType the message type to filter by, or {@code -1} for all types
     * @param maxResults  maximum number of messages to return
     * @return a list of chat messages
     */
    List<ChatMessage> queryChatHistory(int messageType, int maxResults);

    /**
     * Returns the text content of a chat message by index.
     *
     * @param index the message index in the chat history
     * @return the message text
     */
    String getChatMessageText(int index);

    /**
     * Returns the player name associated with a chat message.
     *
     * @param index the message index in the chat history
     * @return the player name, or {@code null} for system messages
     */
    String getChatMessagePlayer(int index);

    /**
     * Returns the type of a chat message.
     *
     * @param index the message index in the chat history
     * @return the message type ID
     */
    int getChatMessageType(int index);

    /**
     * Returns the total number of messages in the chat history.
     *
     * @return the chat history size
     */
    int getChatHistorySize();

    // ============================== Navigation & Pathfinding ==============================

    /**
     * Starts a local A* walk to the given tile. Returns immediately; the walk
     * happens asynchronously on the game thread. Listen for
     * {@link com.botwithus.bot.api.event.WalkArrivedEvent},
     * {@link com.botwithus.bot.api.event.WalkFailedEvent}, or
     * {@link com.botwithus.bot.api.event.WalkCancelledEvent} for completion.
     *
     * @param x target world tile X
     * @param y target world tile Y
     */
    void walkToAsync(int x, int y);

    /**
     * Starts a world-scale walk using HPA&#42;/flat A&#42; with teleport, door,
     * shortcut, and transport support. Returns immediately; the walk
     * happens asynchronously. Listen for walk events for completion.
     *
     * @param x     target world tile X
     * @param y     target world tile Y
     * @param plane target plane (height level)
     */
    void walkWorldPathAsync(int x, int y, int plane);

    /**
     * Starts a world-scale walk with exact destination tile control.
     *
     * @param x             target world tile X
     * @param y             target world tile Y
     * @param plane         target plane (height level)
     * @param exactDestTile if {@code true}, click the exact destination tile with no variance when in range
     */
    default void walkWorldPathAsync(int x, int y, int plane, boolean exactDestTile) {
        walkWorldPathAsync(x, y, plane, exactDestTile, null);
    }

    /**
     * Starts a world-scale walk with full pathfinder configuration.
     *
     * @param x             target world tile X
     * @param y             target world tile Y
     * @param plane         target plane (height level)
     * @param exactDestTile if {@code true}, click the exact destination tile with no variance when in range
     * @param config        pathfinder config overrides, or {@code null} for defaults
     */
    void walkWorldPathAsync(int x, int y, int plane, boolean exactDestTile, WorldPathConfig config);

    /**
     * Cancels any active walk. Emits {@code walk_cancelled} if a walk was in progress.
     */
    void walkCancel();

    /**
     * Returns the current walker state.
     *
     * @return the walk status
     */
    WalkStatus getWalkStatus();

    /**
     * Checks if a tile is reachable from the player's current position
     * via local A* pathfinding.
     *
     * @param x target world X
     * @param y target world Y
     * @return {@code true} if the tile is reachable
     */
    boolean isReachable(int x, int y);

    /**
     * Checks if a tile is reachable with a custom iteration limit.
     *
     * @param x             target world X
     * @param y             target world Y
     * @param maxIterations max A* iterations
     * @return {@code true} if the tile is reachable
     */
    boolean isReachable(int x, int y, int maxIterations);

    /**
     * Finds a local A* path between two tiles.
     *
     * @param toX destination world X
     * @param toY destination world Y
     * @return the path result
     */
    PathResult findPath(int toX, int toY);

    /**
     * Finds a local A* path from a specific origin.
     *
     * @param fromX origin world X
     * @param fromY origin world Y
     * @param toX   destination world X
     * @param toY   destination world Y
     * @return the path result
     */
    PathResult findPath(int fromX, int fromY, int toX, int toY);

    /**
     * Finds a world-scale path without walking it. Uses RegionGraph pathfinding.
     *
     * @param toX destination world X
     * @param toY destination world Y
     * @return the path result
     */
    PathResult findWorldPath(int toX, int toY);

    /**
     * Finds a world-scale path from a specific origin without walking it.
     *
     * @param fromX origin world X
     * @param fromY origin world Y
     * @param toX   destination world X
     * @param toY   destination world Y
     * @return the path result
     */
    PathResult findWorldPath(int fromX, int fromY, int toX, int toY);

    /**
     * Returns the region collision cache size.
     *
     * @return the number of cached regions
     */
    int getRegionCacheSize();

    /**
     * Invalidates all cached region collision data.
     */
    void clearRegionCache();

    // ============================== Navigation Links & Teleports ==============================

    /**
     * Adds a transport link to the navigation graph.
     *
     * @param transport the transport link to add
     */
    void navAddTransport(NavTransport transport);

    /**
     * Removes a transport link from the navigation graph.
     *
     * @param objectId game object ID
     * @param x        world tile X
     * @param y        world tile Y
     * @param plane    plane
     */
    void navRemoveTransport(int objectId, int x, int y, int plane);

    /**
     * Lists all transport links in the navigation graph.
     *
     * @return a list of transport links
     */
    List<NavTransport> navListTransports();

    /**
     * Adds a door to the navigation graph.
     *
     * @param door the door to add
     */
    void navAddDoor(NavDoor door);

    /**
     * Removes a door from the navigation graph.
     *
     * @param objectId game object ID
     * @param x        world tile X
     * @param y        world tile Y
     * @param plane    plane
     */
    void navRemoveDoor(int objectId, int x, int y, int plane);

    /**
     * Lists all doors in the navigation graph.
     *
     * @return a list of doors
     */
    List<NavDoor> navListDoors();

    /**
     * Adds an agility shortcut to the navigation graph.
     *
     * @param shortcut the shortcut to add
     */
    void navAddShortcut(NavShortcut shortcut);

    /**
     * Removes a shortcut from the navigation graph.
     *
     * @param objectId game object ID
     * @param x        world tile X
     * @param y        world tile Y
     * @param plane    plane
     */
    void navRemoveShortcut(int objectId, int x, int y, int plane);

    /**
     * Adds a plane (floor level) transition to the navigation graph.
     *
     * @param transition the plane transition to add
     */
    void navAddPlaneTransition(NavPlaneTransition transition);

    /**
     * Removes a plane transition from the navigation graph.
     *
     * @param objectId game object ID
     * @param x        world tile X
     * @param y        world tile Y
     * @param plane    plane
     */
    void navRemovePlaneTransition(int objectId, int x, int y, int plane);

    /**
     * Adds a climbover obstacle to the navigation graph.
     *
     * @param climbover the climbover to add
     */
    void navAddClimbover(NavClimbover climbover);

    /**
     * Removes a climbover from the navigation graph.
     *
     * @param objectId game object ID
     * @param x        world tile X
     * @param y        world tile Y
     * @param plane    plane
     */
    void navRemoveClimbover(int objectId, int x, int y, int plane);

    /**
     * Batch-adds transport links to the navigation graph.
     *
     * @param links the transport links to add
     * @return the number of links added
     */
    int navLoadJson(List<NavTransport> links);

    /**
     * Saves all navigation overrides to a binary file.
     *
     * @param path output file path (or {@code null} for default "nav_overrides.bin")
     */
    void navSaveLinks(String path);

    /**
     * Loads navigation overrides from a binary file.
     *
     * @param path input file path (or {@code null} for default "nav_overrides.bin")
     * @return the number of links loaded
     */
    int navLoadLinks(String path);

    /**
     * Returns navigation data statistics.
     *
     * @return the nav stats
     */
    NavStats navGetStats();

    /**
     * Registers custom teleports for the pathfinder.
     *
     * @param json   JSON string containing teleport definitions
     * @param format format: {@code "item_teleports"} or {@code "gibson"}
     * @return the number of teleports added
     */
    int navRegisterTeleports(String json, String format);

    /**
     * Removes all script-registered teleports. Built-in teleports are preserved.
     *
     * @return the number of teleports removed
     */
    int navClearScriptTeleports();

    /**
     * Lists registered teleports.
     *
     * @param scriptOnly if {@code true}, only list script-registered teleports
     * @return a list of teleports
     */
    List<NavTeleport> navListTeleports(boolean scriptOnly);

    // ============================== Config Type Lookups ==============================

    /**
     * Returns an item definition from the game cache.
     *
     * @param id the item ID
     * @return the item type definition
     */
    ItemType getItemType(int id);

    /**
     * Returns an NPC definition from the game cache.
     *
     * @param id the NPC type ID
     * @return the NPC type definition
     */
    NpcType getNpcType(int id);

    /**
     * Returns a location (game object) definition from the game cache.
     *
     * @param id the location type ID
     * @return the location type definition
     */
    LocationType getLocationType(int id);

    /**
     * Returns an enum (key-value mapping) definition from the game cache.
     * Enums map input keys to output values (e.g., skill IDs to skill names).
     *
     * @param id the enum type ID
     * @return the enum type definition
     */
    EnumType getEnumType(int id);

    /**
     * Returns a struct definition from the game cache.
     * Structs are parameter bags containing key-value pairs.
     *
     * @param id the struct type ID
     * @return the struct type definition
     */
    StructType getStructType(int id);

    /**
     * Returns an animation sequence definition from the game cache.
     *
     * @param id the sequence type ID
     * @return the sequence type definition
     */
    SequenceType getSequenceType(int id);

    /**
     * Returns a quest definition from the game cache.
     *
     * @param id the quest type ID
     * @return the quest type definition
     */
    QuestType getQuestType(int id);
}
