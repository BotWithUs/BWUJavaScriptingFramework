package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.model.*;
import com.botwithus.bot.api.query.WorldMapElementFilter;

import java.util.List;

/**
 * Game state queries: local player, login state, worlds, screen coordinates,
 * cache lookups, screenshots, streaming, and humanization.
 *
 * @see com.botwithus.bot.api.GameAPI
 */
public interface GameStateAPI {

    // ============================== Player & Session ==============================

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

    // ============================== Screen & Viewport ==============================

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
     * Initiates a world hop to the specified world.
     *
     * @param worldId the target world ID
     */
    void setWorld(int worldId);

    // ============================== Login & Breaks ==============================

    /**
     * Requests a login state transition.
     *
     * @param oldState the expected current state
     * @param newState the desired new state
     */
    void changeLoginState(int oldState, int newState);

    /**
     * Triggers a login from the login screen to lobby.
     *
     * @throws RuntimeException if the client is not on the login screen
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

    // ============================== World Map Elements ==============================

    /**
     * Queries world map elements (icons) from the game cache with optional filters.
     * These are static map features like banks, altars, and dungeons.
     *
     * @param filter the world map element filter criteria
     * @return a list of matching world map elements
     * @see WorldMapElementFilter
     */
    List<WorldMapElement> queryWorldMapElements(WorldMapElementFilter filter);

    /**
     * Returns a single world map element by its ID.
     *
     * @param id the world map element ID
     * @return the world map element, or {@code null} if not found
     */
    WorldMapElement getWorldMapElement(int id);

    /**
     * Returns the total number of loaded world map elements.
     *
     * @return the element count
     */
    int getWorldMapElementCount();

    // ============================== Cache ==============================

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
     * Retrieves the navigation archive from the game cache.
     *
     * @return the navigation archive cache file
     */
    CacheFile getNavigationArchive();

    // ============================== Screenshots & Streaming ==============================

    /**
     * Captures a screenshot of the game framebuffer as a PNG image.
     *
     * @return the screenshot as a cache file containing PNG data
     */
    CacheFile takeScreenshot();

    /**
     * Starts continuous JPEG frame streaming over a dedicated named pipe.
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
     * Checks whether input humanization is enabled.
     *
     * @return {@code true} if humanization is enabled
     */
    boolean getHumanizationEnabled();

    /**
     * Enables or disables input humanization.
     *
     * @param enabled {@code true} to enable, {@code false} to disable
     */
    void setHumanizationEnabled(boolean enabled);

    /**
     * Returns the current humanizer personality profile and live session statistics.
     *
     * @return the personality profile, or {@code null} if the humanizer is not initialized
     */
    Personality getPersonality();

    // ============================== Script Execution ==============================

    /**
     * Obtains a handle to a client script for repeated execution.
     *
     * @param scriptId the client script ID
     * @return the script handle
     */
    long getScriptHandle(int scriptId);

    /**
     * Executes a client script with the given arguments.
     *
     * @param handle     the script handle
     * @param intArgs    integer arguments
     * @param stringArgs string arguments
     * @param returns    expected return type descriptors
     * @return the script execution result
     */
    ScriptResult executeScript(long handle, int[] intArgs, String[] stringArgs, String[] returns);

    /**
     * Releases a script handle.
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
}
