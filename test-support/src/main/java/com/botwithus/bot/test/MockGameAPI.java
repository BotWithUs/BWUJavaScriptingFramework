package com.botwithus.bot.test;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.GroundItems;
import com.botwithus.bot.api.entities.Npcs;
import com.botwithus.bot.api.entities.Players;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.entities.WorldMapElements;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.inventory.Bank;
import com.botwithus.bot.api.inventory.Equipment;
import com.botwithus.bot.api.model.ActionEntry;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.GroundItemInfo;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.model.LoginState;
import com.botwithus.bot.api.model.NpcType;
import com.botwithus.bot.api.model.PathResult;
import com.botwithus.bot.api.model.PlayerStat;
import com.botwithus.bot.api.model.QuestType;
import com.botwithus.bot.api.model.SceneObjectInfo;
import com.botwithus.bot.api.model.ScriptResult;
import com.botwithus.bot.api.model.SequenceType;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.api.model.WalkStatus;
import com.botwithus.bot.api.model.WorldMapElement;
import com.botwithus.bot.api.model.WorldPathConfig;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Minimal {@link GameAPI} stub for dry-run script tests.
 *
 * <p>Only the methods a typical {@code onStart}/{@code onLoop}/{@code onStop}
 * actually call are implemented:
 * <ul>
 *   <li>{@link #snapshot()} — delegates to the configured supplier</li>
 *   <li>{@link #getLocalPlayer()} — returns {@code snapshot().self()}</li>
 *   <li>{@link #queueAction(GameAction)} — forwards to the recording sink</li>
 *   <li>{@link #queueActions(List)} — forwarded one-by-one to the sink</li>
 * </ul>
 *
 * <p>Every other method throws {@link UnsupportedOperationException} with a
 * pointer at which seam to configure. The intent is exactly the opposite of
 * a "friendly mock that returns sensible defaults" — tests should be loud
 * about what they exercise. If your script reaches an unstubbed method, that
 * is a signal the test needs to either supply a real {@link GameAPI} or the
 * script needs to be split into a smaller unit.</p>
 */
final class MockGameAPI implements GameAPI {

    private final Supplier<GameSnapshot> snapshotSource;
    private final Consumer<GameAction> actionSink;

    MockGameAPI(Supplier<GameSnapshot> snapshotSource, Consumer<GameAction> actionSink) {
        if (snapshotSource == null) {
            throw new IllegalArgumentException("snapshotSource");
        }
        if (actionSink == null) {
            throw new IllegalArgumentException("actionSink");
        }
        this.snapshotSource = snapshotSource;
        this.actionSink = actionSink;
    }

    private static UnsupportedOperationException notStubbed(String method) {
        return new UnsupportedOperationException(
                "MockGameAPI." + method + " not stubbed — provide a real GameAPI for tests that reach this seam");
    }

    @Override
    public GameSnapshot snapshot() {
        return snapshotSource.get();
    }

    @Override
    public LocalPlayer getLocalPlayer() {
        GameSnapshot snap = snapshot();
        return snap == null ? null : snap.self();
    }

    @Override
    public void queueAction(GameAction action) {
        actionSink.accept(action);
    }

    @Override
    public int queueActions(List<GameAction> actions) {
        actions.forEach(actionSink);
        return actions.size();
    }

    // ---- SystemAPI ---------------------------------------------------------

    @Override
    public boolean ping() {
        throw notStubbed("ping");
    }

    @Override
    public List<String> listMethods() {
        throw notStubbed("listMethods");
    }

    @Override
    public int getClientCount() {
        throw notStubbed("getClientCount");
    }

    // ---- ActionAPI ---------------------------------------------------------

    @Override
    public int getActionQueueSize() {
        throw notStubbed("getActionQueueSize");
    }

    @Override
    public void clearActionQueue() {
        throw notStubbed("clearActionQueue");
    }

    @Override
    public List<ActionEntry> getActionHistory(int maxResults, int actionIdFilter) {
        throw notStubbed("getActionHistory");
    }

    @Override
    public long getLastActionTime() {
        throw notStubbed("getLastActionTime");
    }

    @Override
    public boolean areActionsBlocked() {
        throw notStubbed("areActionsBlocked");
    }

    @Override
    public void setActionsBlocked(boolean blocked) {
        throw notStubbed("setActionsBlocked");
    }

    // ---- NavigationAPI -----------------------------------------------------

    @Override
    public void walkToAsync(int x, int y) {
        throw notStubbed("walkToAsync");
    }

    @Override
    public void walkWorldPathAsync(int x, int y, int plane) {
        throw notStubbed("walkWorldPathAsync");
    }

    @Override
    public void walkWorldPathAsync(int x, int y, int plane, boolean exactDestTile, WorldPathConfig config) {
        throw notStubbed("walkWorldPathAsync");
    }

    @Override
    public void walkCancel() {
        throw notStubbed("walkCancel");
    }

    @Override
    public WalkStatus getWalkStatus() {
        throw notStubbed("getWalkStatus");
    }

    @Override
    public boolean isReachable(int x, int y) {
        throw notStubbed("isReachable");
    }

    @Override
    public boolean isReachable(int x, int y, int maxIterations) {
        throw notStubbed("isReachable");
    }

    @Override
    public PathResult findPath(int toX, int toY) {
        throw notStubbed("findPath");
    }

    @Override
    public PathResult findPath(int fromX, int fromY, int toX, int toY) {
        throw notStubbed("findPath");
    }

    @Override
    public PathResult findWorldPath(int toX, int toY) {
        throw notStubbed("findWorldPath");
    }

    @Override
    public PathResult findWorldPath(int fromX, int fromY, int toX, int toY) {
        throw notStubbed("findWorldPath");
    }

    @Override
    public int getRegionCacheSize() {
        throw notStubbed("getRegionCacheSize");
    }

    @Override
    public void clearRegionCache() {
        throw notStubbed("clearRegionCache");
    }

    // ---- Entity / inventory facades ----------------------------------------

    @Override
    public Npcs npcs() {
        throw notStubbed("npcs");
    }

    @Override
    public Players players() {
        throw notStubbed("players");
    }

    @Override
    public Backpack backpack() {
        throw notStubbed("backpack");
    }

    @Override
    public Bank bank() {
        throw notStubbed("bank");
    }

    @Override
    public Equipment equipment() {
        throw notStubbed("equipment");
    }

    @Override
    public SceneObjects objects() {
        throw notStubbed("objects");
    }

    @Override
    public GroundItems groundItems() {
        throw notStubbed("groundItems");
    }

    @Override
    public WorldMapElements mapElements() {
        throw notStubbed("mapElements");
    }

    @Override
    public List<SceneObjectInfo> queryLocations(int centerX, int centerY, int radius, int plane, int max) {
        throw notStubbed("queryLocations");
    }

    @Override
    public List<GroundItemInfo> queryGroundItems(int centerX, int centerY, int radius, int plane, int max) {
        throw notStubbed("queryGroundItems");
    }

    @Override
    public List<WorldMapElement> queryWorldMapElements(Map<String, Object> filter) {
        throw notStubbed("queryWorldMapElements");
    }

    @Override
    public PlayerStat getPlayerStat(int skillId) {
        throw notStubbed("getPlayerStat");
    }

    // ---- State probes ------------------------------------------------------

    @Override
    public int getGameCycle() {
        throw notStubbed("getGameCycle");
    }

    @Override
    public LoginState getLoginState() {
        throw notStubbed("getLoginState");
    }

    // ---- Login / breaks ----------------------------------------------------

    @Override
    public void setWorld(int worldId) {
        throw notStubbed("setWorld");
    }

    @Override
    public void changeLoginState(int oldState, int newState) {
        throw notStubbed("changeLoginState");
    }

    @Override
    public void loginToLobby() {
        throw notStubbed("loginToLobby");
    }

    @Override
    public void scheduleBreak(int durationMs) {
        throw notStubbed("scheduleBreak");
    }

    @Override
    public void interruptBreak() {
        throw notStubbed("interruptBreak");
    }

    @Override
    public boolean getAutoLogin() {
        throw notStubbed("getAutoLogin");
    }

    @Override
    public void setAutoLogin(boolean enabled) {
        throw notStubbed("setAutoLogin");
    }

    // ---- Script execution --------------------------------------------------

    @Override
    public long getScriptHandle(int scriptId) {
        throw notStubbed("getScriptHandle");
    }

    @Override
    public ScriptResult executeScript(long handle, int[] intArgs, String[] stringArgs, String[] returns) {
        throw notStubbed("executeScript");
    }

    @Override
    public void destroyScriptHandle(long handle) {
        throw notStubbed("destroyScriptHandle");
    }

    @Override
    public void fireKeyTrigger(int interfaceId, int componentId, String input) {
        throw notStubbed("fireKeyTrigger");
    }

    // ---- Interface tree walk -----------------------------------------------

    @Override
    public Component getComponent(int interfaceId, int componentId) {
        throw notStubbed("getComponent");
    }

    @Override
    public List<Integer> getStaticChildren(int interfaceId, int componentId) {
        throw notStubbed("getStaticChildren");
    }

    @Override
    public List<Integer> getDynamicChildren(int interfaceId, int componentId) {
        throw notStubbed("getDynamicChildren");
    }

    // ---- Config-type lookups -----------------------------------------------

    @Override
    public ItemType getItemType(int id) {
        throw notStubbed("getItemType");
    }

    @Override
    public NpcType getNpcType(int id) {
        throw notStubbed("getNpcType");
    }

    @Override
    public LocationType getLocationType(int id) {
        throw notStubbed("getLocationType");
    }

    @Override
    public EnumType getEnumType(int id) {
        throw notStubbed("getEnumType");
    }

    @Override
    public StructType getStructType(int id) {
        throw notStubbed("getStructType");
    }

    @Override
    public SequenceType getSequenceType(int id) {
        throw notStubbed("getSequenceType");
    }

    @Override
    public QuestType getQuestType(int id) {
        throw notStubbed("getQuestType");
    }
}
