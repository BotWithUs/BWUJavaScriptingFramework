package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.*;
import com.botwithus.bot.api.query.ComponentFilter;
import com.botwithus.bot.api.query.EntityFilter;
import com.botwithus.bot.api.query.InventoryFilter;
import com.botwithus.bot.core.rpc.RpcClient;

import java.util.*;

import static com.botwithus.bot.core.impl.MapHelper.*;

public class GameAPIImpl implements GameAPI {

    private final RpcClient rpc;

    public GameAPIImpl(RpcClient rpc) {
        this.rpc = rpc;
    }

    // ========================== System ==========================

    @Override
    public boolean ping() {
        Map<String, Object> r = rpc.callSync("rpc.ping", Map.of());
        return getBool(r, "pong");
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> listMethods() {
        Object raw = rpc.callSyncRaw("rpc.list_methods", Map.of());
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    @Override
    public void subscribe(String event) {
        rpc.callSync("rpc.subscribe", Map.of("event", event));
    }

    @Override
    public void unsubscribe(String event) {
        rpc.callSync("rpc.unsubscribe", Map.of("event", event));
    }

    @Override
    public int getClientCount() {
        Map<String, Object> r = rpc.callSync("rpc.client_count", Map.of());
        return getInt(r, "count");
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> listEvents() {
        Object raw = rpc.callSyncRaw("rpc.list_events", Map.of());
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getSubscriptions() {
        Object raw = rpc.callSyncRaw("rpc.get_subscriptions", Map.of());
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    // ========================== Actions ==========================

    @Override
    public void queueAction(GameAction action) {
        rpc.callSync("queue_action", Map.of(
                "action_id", action.actionId(),
                "param1", action.param1(),
                "param2", action.param2(),
                "param3", action.param3()
        ));
    }

    @Override
    public int queueActions(List<GameAction> actions) {
        List<Map<String, Object>> actionList = actions.stream()
                .map(a -> Map.<String, Object>of(
                        "action_id", a.actionId(), "param1", a.param1(),
                        "param2", a.param2(), "param3", a.param3()))
                .toList();
        Map<String, Object> r = rpc.callSync("queue_actions", Map.of("actions", actionList));
        return getInt(r, "queued");
    }

    @Override
    public int getActionQueueSize() {
        Map<String, Object> r = rpc.callSync("get_action_queue_size", Map.of());
        return getInt(r, "size");
    }

    @Override
    public void clearActionQueue() {
        rpc.callSync("clear_action_queue", Map.of());
    }

    @Override
    public List<ActionEntry> getActionHistory(int maxResults, int actionIdFilter) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("max_results", maxResults);
        params.put("action_id_filter", actionIdFilter);
        return rpc.callSyncList("get_action_history", params).stream()
                .map(m -> new ActionEntry(
                        getInt(m, "action_id"), getInt(m, "param1"), getInt(m, "param2"), getInt(m, "param3"),
                        getLong(m, "timestamp"), getLong(m, "delta")))
                .toList();
    }

    @Override
    public long getLastActionTime() {
        Map<String, Object> r = rpc.callSync("get_last_action_time", Map.of());
        return getLong(r, "timestamp");
    }

    @Override
    public void setBehaviorMod(int modId, float value) {
        rpc.callSync("set_behavior_mod", Map.of("mod_id", modId, "value", value));
    }

    @Override
    public void clearBehaviorMod(int modId) {
        rpc.callSync("clear_behavior_mod", Map.of("mod_id", modId));
    }

    @Override
    public float getBehaviorMod(int modId) {
        Map<String, Object> r = rpc.callSync("get_behavior_mod", Map.of("mod_id", modId));
        return getFloat(r, "value");
    }

    @Override
    public boolean areActionsBlocked() {
        Map<String, Object> r = rpc.callSync("are_actions_blocked", Map.of());
        return getBool(r, "blocked");
    }

    @Override
    public void setActionsBlocked(boolean blocked) {
        rpc.callSync("set_actions_blocked", Map.of("blocked", blocked));
    }

    // ========================== Entity Queries ==========================

    @Override
    public List<Entity> queryEntities(EntityFilter filter) {
        return rpc.callSyncList("query_entities", filter.toParams()).stream()
                .map(this::mapEntity).toList();
    }

    @Override
    public EntityInfo getEntityInfo(int handle) {
        Map<String, Object> r = rpc.callSync("get_entity_info", Map.of("handle", handle));
        return new EntityInfo(
                getInt(r, "handle"), getInt(r, "server_index"), getInt(r, "type_id"),
                getInt(r, "tile_x"), getInt(r, "tile_y"), getInt(r, "tile_z"),
                getString(r, "name"), getInt(r, "name_hash"),
                getBool(r, "is_moving"), getBool(r, "is_hidden"),
                getInt(r, "health"), getInt(r, "max_health"),
                getInt(r, "animation_id"), getInt(r, "stance_id"),
                getInt(r, "following_index"),
                getString(r, "overhead_text"), getInt(r, "combat_level")
        );
    }

    @Override
    public String getEntityName(int handle) {
        Map<String, Object> r = rpc.callSync("get_entity_name", Map.of("handle", handle));
        return getString(r, "name");
    }

    @Override
    public EntityHealth getEntityHealth(int handle) {
        Map<String, Object> r = rpc.callSync("get_entity_health", Map.of("handle", handle));
        return new EntityHealth(getInt(r, "health"), getInt(r, "max_health"));
    }

    @Override
    public EntityPosition getEntityPosition(int handle) {
        Map<String, Object> r = rpc.callSync("get_entity_position", Map.of("handle", handle));
        return new EntityPosition(getInt(r, "tile_x"), getInt(r, "tile_y"), getInt(r, "plane"));
    }

    @Override
    public boolean isEntityValid(int handle) {
        Map<String, Object> r = rpc.callSync("is_entity_valid", Map.of("handle", handle));
        return getBool(r, "valid");
    }

    @Override
    public List<Hitmark> getEntityHitmarks(int handle) {
        return rpc.callSyncList("get_entity_hitmarks", Map.of("handle", handle)).stream()
                .map(m -> new Hitmark(getInt(m, "damage"), getInt(m, "type"), getInt(m, "cycle")))
                .toList();
    }

    @Override
    public int getEntityAnimation(int handle) {
        Map<String, Object> r = rpc.callSync("get_entity_animation", Map.of("handle", handle));
        return getInt(r, "animation_id");
    }

    @Override
    public String getEntityOverheadText(int handle) {
        Map<String, Object> r = rpc.callSync("get_entity_overhead_text", Map.of("handle", handle));
        return getString(r, "text");
    }

    @Override
    public int getAnimationLength(int animationId) {
        Map<String, Object> r = rpc.callSync("get_animation_length", Map.of("animation_id", animationId));
        return getInt(r, "length");
    }

    // ========================== Ground Items ==========================

    @Override
    public List<GroundItemStack> queryGroundItems(EntityFilter filter) {
        return rpc.callSyncList("query_ground_items", filter.toParams()).stream()
                .map(this::mapGroundItemStack).toList();
    }

    @Override
    public List<GroundItem> getObjStackItems(int handle) {
        return rpc.callSyncList("get_obj_stack_items", Map.of("handle", handle)).stream()
                .map(m -> new GroundItem(getInt(m, "item_id"), getInt(m, "quantity")))
                .toList();
    }

    @Override
    public List<Entity> queryObjStacks(EntityFilter filter) {
        return rpc.callSyncList("query_obj_stacks", filter.toParams()).stream()
                .map(this::mapEntity).toList();
    }

    // ========================== Projectiles ==========================

    @Override
    public List<Projectile> queryProjectiles(int projectileId, int plane, int maxResults) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (projectileId >= 0) params.put("projectile_id", projectileId);
        if (plane >= 0) params.put("plane", plane);
        if (maxResults > 0) params.put("max_results", maxResults);
        return rpc.callSyncList("query_projectiles", params).stream()
                .map(m -> new Projectile(
                        getInt(m, "handle"), getInt(m, "projectile_id"),
                        getInt(m, "start_x"), getInt(m, "start_y"),
                        getInt(m, "end_x"), getInt(m, "end_y"), getInt(m, "plane"),
                        getInt(m, "target_index"), getInt(m, "source_index"),
                        getInt(m, "start_cycle"), getInt(m, "end_cycle")))
                .toList();
    }

    // ========================== Spot Anims ==========================

    @Override
    public List<SpotAnim> querySpotAnims(int animId, int plane, int maxResults) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (animId >= 0) params.put("anim_id", animId);
        if (plane >= 0) params.put("plane", plane);
        if (maxResults > 0) params.put("max_results", maxResults);
        return rpc.callSyncList("query_spot_anims", params).stream()
                .map(m -> new SpotAnim(getInt(m, "handle"), getInt(m, "anim_id"),
                        getInt(m, "tile_x"), getInt(m, "tile_y"), getInt(m, "tile_z")))
                .toList();
    }

    // ========================== Hint Arrows ==========================

    @Override
    public List<HintArrow> queryHintArrows(int maxResults) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (maxResults > 0) params.put("max_results", maxResults);
        return rpc.callSyncList("query_hint_arrows", params).stream()
                .map(m -> new HintArrow(getInt(m, "handle"), getInt(m, "type"),
                        getInt(m, "tile_x"), getInt(m, "tile_y"), getInt(m, "tile_z"),
                        getInt(m, "target_index")))
                .toList();
    }

    // ========================== Worlds ==========================

    @Override
    public List<World> queryWorlds(boolean includeActivity) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (includeActivity) params.put("include_activity", true);
        return rpc.callSyncList("query_worlds", params).stream()
                .map(this::mapWorld).toList();
    }

    @Override
    public World getCurrentWorld() {
        Map<String, Object> r = rpc.callSync("get_current_world", Map.of());
        return mapWorld(r);
    }

    @Override
    public int computeNameHash(String name) {
        Map<String, Object> r = rpc.callSync("compute_name_hash", Map.of("name", name));
        return getInt(r, "hash");
    }

    @Override
    public void updateQueryContext() {
        rpc.callSync("update_query_context", Map.of());
    }

    @Override
    public void invalidateQueryContext() {
        rpc.callSync("invalidate_query_context", Map.of());
    }

    // ========================== Components & Interfaces ==========================

    @Override
    public List<Component> queryComponents(ComponentFilter filter) {
        return rpc.callSyncList("query_components", filter.toParams()).stream()
                .map(this::mapComponent).toList();
    }

    @Override
    public boolean isComponentValid(int interfaceId, int componentId, int subComponentId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("interface_id", interfaceId);
        params.put("component_id", componentId);
        if (subComponentId >= 0) params.put("sub_component_id", subComponentId);
        Map<String, Object> r = rpc.callSync("is_component_valid", params);
        return getBool(r, "valid");
    }

    @Override
    public String getComponentText(int interfaceId, int componentId) {
        Map<String, Object> r = rpc.callSync("get_component_text",
                Map.of("interface_id", interfaceId, "component_id", componentId));
        return getString(r, "text");
    }

    @Override
    public InventoryItem getComponentItem(int interfaceId, int componentId, int subComponentId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("interface_id", interfaceId);
        params.put("component_id", componentId);
        if (subComponentId >= 0) params.put("sub_component_id", subComponentId);
        Map<String, Object> r = rpc.callSync("get_component_item", params);
        return new InventoryItem(0, getInt(r, "item_id"), getInt(r, "count"), 0);
    }

    @Override
    public ComponentPosition getComponentPosition(int interfaceId, int componentId) {
        Map<String, Object> r = rpc.callSync("get_component_position",
                Map.of("interface_id", interfaceId, "component_id", componentId));
        return new ComponentPosition(getInt(r, "x"), getInt(r, "y"), getInt(r, "width"), getInt(r, "height"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getComponentOptions(int interfaceId, int componentId) {
        Object raw = rpc.callSyncRaw("get_component_options",
                Map.of("interface_id", interfaceId, "component_id", componentId));
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    @Override
    public int getComponentSpriteId(int interfaceId, int componentId) {
        Map<String, Object> r = rpc.callSync("get_component_sprite_id",
                Map.of("interface_id", interfaceId, "component_id", componentId));
        return getInt(r, "sprite_id");
    }

    @Override
    public ComponentTypeInfo getComponentType(int interfaceId, int componentId) {
        Map<String, Object> r = rpc.callSync("get_component_type",
                Map.of("interface_id", interfaceId, "component_id", componentId));
        return new ComponentTypeInfo(getInt(r, "type"), getString(r, "type_name"));
    }

    @Override
    public List<Component> getComponentChildren(int interfaceId, int componentId) {
        return rpc.callSyncList("get_component_children",
                Map.of("interface_id", interfaceId, "component_id", componentId)).stream()
                .map(this::mapComponent).toList();
    }

    @Override
    public int getComponentByHash(int interfaceId, int componentId, int subComponentId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("interface_id", interfaceId);
        params.put("component_id", componentId);
        if (subComponentId >= 0) params.put("sub_component_id", subComponentId);
        Map<String, Object> r = rpc.callSync("get_component_by_hash", params);
        return getInt(r, "handle");
    }

    @Override
    public List<OpenInterface> getOpenInterfaces() {
        return rpc.callSyncList("get_open_interfaces", Map.of()).stream()
                .map(m -> new OpenInterface(getInt(m, "parent_hash"), getInt(m, "interface_id")))
                .toList();
    }

    @Override
    public boolean isInterfaceOpen(int interfaceId) {
        Map<String, Object> r = rpc.callSync("is_interface_open", Map.of("interface_id", interfaceId));
        return getBool(r, "open");
    }

    // ========================== Game Variables ==========================

    @Override
    public int getVarp(int varId) {
        Map<String, Object> r = rpc.callSync("get_varp", Map.of("var_id", varId));
        return getInt(r, "value");
    }

    @Override
    public int getVarbit(int varbitId) {
        Map<String, Object> r = rpc.callSync("get_varbit", Map.of("varbit_id", varbitId));
        return getInt(r, "value");
    }

    @Override
    public int getVarcInt(int varcId) {
        Map<String, Object> r = rpc.callSync("get_varc_int", Map.of("varc_id", varcId));
        return getInt(r, "value");
    }

    @Override
    public String getVarcString(int varcId) {
        Map<String, Object> r = rpc.callSync("get_varc_string", Map.of("varc_id", varcId));
        return getString(r, "value");
    }

    @Override
    public List<VarbitValue> queryVarbits(List<Integer> varbitIds) {
        return rpc.callSyncList("query_varbits", Map.of("varbit_ids", varbitIds)).stream()
                .map(m -> new VarbitValue(getInt(m, "varbit_id"), getInt(m, "value")))
                .toList();
    }

    // ========================== Script Execution ==========================

    @Override
    public long getScriptHandle(int scriptId) {
        Map<String, Object> r = rpc.callSync("get_script_handle", Map.of("script_id", scriptId));
        return getLong(r, "handle");
    }

    @Override
    public ScriptResult executeScript(long handle, int[] intArgs, String[] stringArgs, String[] returns) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("handle", handle);
        if (intArgs != null && intArgs.length > 0) {
            List<Integer> intList = new ArrayList<>();
            for (int a : intArgs) intList.add(a);
            params.put("int_args", intList);
        }
        if (stringArgs != null && stringArgs.length > 0) {
            params.put("string_args", List.of(stringArgs));
        }
        if (returns != null && returns.length > 0) {
            params.put("returns", List.of(returns));
        }
        Map<String, Object> r = rpc.callSync("execute_script", params);
        @SuppressWarnings("unchecked")
        List<Object> returnValues = r.containsKey("returns") ? (List<Object>) r.get("returns") : List.of();
        return new ScriptResult(returnValues);
    }

    @Override
    public void destroyScriptHandle(long handle) {
        rpc.callSync("destroy_script_handle", Map.of("handle", handle));
    }

    @Override
    public void fireKeyTrigger(int interfaceId, int componentId, String input) {
        rpc.callSync("fire_key_trigger", Map.of(
                "interface_id", interfaceId, "component_id", componentId, "input", input));
    }

    // ========================== Game State ==========================

    @Override
    public LocalPlayer getLocalPlayer() {
        Map<String, Object> r = rpc.callSync("get_local_player", Map.of());
        return new LocalPlayer(
                getInt(r, "server_index"), getString(r, "name"),
                getInt(r, "tile_x"), getInt(r, "tile_y"), getInt(r, "plane"),
                getBool(r, "is_member"), getBool(r, "is_moving"),
                getInt(r, "animation_id"), getInt(r, "stance_id"),
                getInt(r, "health"), getInt(r, "max_health"), getInt(r, "combat_level"),
                getString(r, "overhead_text"), getInt(r, "target_index"), getInt(r, "target_type")
        );
    }

    @Override
    public AccountInfo getAccountInfo() {
        Map<String, Object> r = rpc.callSync("get_account_info", Map.of());
        return new AccountInfo(
                getInt(r, "client_type"), getInt(r, "client_state"),
                getString(r, "session_id"), getInt(r, "ip_hash"),
                getString(r, "jx_display_name"), getString(r, "jx_character_id"),
                getString(r, "display_name"), getBool(r, "is_member"),
                getInt(r, "server_index"), getBool(r, "logged_in"),
                getInt(r, "login_progress"), getInt(r, "login_status")
        );
    }

    @Override
    public int getGameCycle() {
        Map<String, Object> r = rpc.callSync("get_game_cycle", Map.of());
        return getInt(r, "cycle");
    }

    @Override
    public LoginState getLoginState() {
        Map<String, Object> r = rpc.callSync("get_login_state", Map.of());
        return new LoginState(getInt(r, "state"), getInt(r, "login_progress"), getInt(r, "login_status"));
    }

    @Override
    public List<MiniMenuEntry> getMiniMenu() {
        return rpc.callSyncList("get_mini_menu", Map.of()).stream()
                .map(m -> new MiniMenuEntry(
                        getString(m, "option_text"), getInt(m, "action_id"), getInt(m, "type_id"),
                        getInt(m, "item_id"), getInt(m, "param1"), getInt(m, "param2"), getInt(m, "param3")))
                .toList();
    }

    @Override
    public List<GrandExchangeOffer> getGrandExchangeOffers() {
        return rpc.callSyncList("get_grand_exchange_offers", Map.of()).stream()
                .map(m -> new GrandExchangeOffer(
                        getInt(m, "slot"), getInt(m, "status"), getInt(m, "type"), getInt(m, "item_id"),
                        getInt(m, "price"), getInt(m, "count"), getInt(m, "completed_count"), getInt(m, "completed_gold")))
                .toList();
    }

    @Override
    public ScreenPosition getWorldToScreen(int tileX, int tileY) {
        Map<String, Object> r = rpc.callSync("get_world_to_screen", Map.of("tile_x", tileX, "tile_y", tileY));
        return new ScreenPosition(getDouble(r, "screen_x"), getDouble(r, "screen_y"));
    }

    @Override
    public List<ScreenPosition> batchWorldToScreen(List<int[]> tiles) {
        List<Map<String, Object>> tileList = tiles.stream()
                .map(t -> Map.<String, Object>of("x", t[0], "y", t[1]))
                .toList();
        Map<String, Object> r = rpc.callSync("batch_world_to_screen", Map.of("tiles", tileList));
        List<Map<String, Object>> results = getList(r, "results");
        return results.stream()
                .map(m -> new ScreenPosition(getDouble(m, "screen_x"), getDouble(m, "screen_y")))
                .toList();
    }

    @Override
    public ViewportInfo getViewportInfo() {
        Map<String, Object> r = rpc.callSync("get_viewport_info", Map.of());
        return new ViewportInfo(
                getInt(r, "viewport_width"), getInt(r, "viewport_height"),
                getFloatArray(r, "projection_matrix"), getFloatArray(r, "view_matrix")
        );
    }

    @Override
    public List<EntityScreenPosition> getEntityScreenPositions(List<Integer> handles) {
        Map<String, Object> r = rpc.callSync("get_entity_screen_positions", Map.of("handles", handles));
        List<Map<String, Object>> results = getList(r, "results");
        return results.stream()
                .map(m -> new EntityScreenPosition(
                        getInt(m, "handle"), getDouble(m, "screen_x"), getDouble(m, "screen_y"), getBool(m, "valid")))
                .toList();
    }

    @Override
    public GameWindowRect getGameWindowRect() {
        Map<String, Object> r = rpc.callSync("get_game_window_rect", Map.of());
        return new GameWindowRect(
                getInt(r, "x"), getInt(r, "y"), getInt(r, "width"), getInt(r, "height"),
                getInt(r, "client_x"), getInt(r, "client_y"), getInt(r, "client_width"), getInt(r, "client_height")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public CacheFile getCacheFile(int indexId, int archiveId, int fileId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("index_id", indexId);
        params.put("archive_id", archiveId);
        if (fileId > 0) params.put("file_id", fileId);
        Map<String, Object> r = rpc.callSync("get_cache_file", params);
        Object data = r.get("data");
        byte[] bytes = data instanceof byte[] b ? b : new byte[0];
        return new CacheFile(bytes, getInt(r, "size"));
    }

    @Override
    public int getCacheFileCount(int indexId, int archiveId, int shift) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("index_id", indexId);
        if (archiveId > 0) params.put("archive_id", archiveId);
        if (shift > 0) params.put("shift", shift);
        Map<String, Object> r = rpc.callSync("get_cache_file_count", params);
        return getInt(r, "count");
    }

    @Override
    public void setWorld(int worldId) {
        rpc.callSync("set_world", Map.of("world_id", worldId));
    }

    @Override
    public void changeLoginState(int oldState, int newState) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (oldState > 0) params.put("old_state", oldState);
        params.put("new_state", newState);
        rpc.callSync("change_login_state", params);
    }

    @Override
    public void loginToLobby() {
        rpc.callSync("login_to_lobby", Map.of());
    }

    @Override
    public void scheduleBreak(int durationMs) {
        rpc.callSync("schedule_break", Map.of("duration", durationMs));
    }

    @Override
    public void interruptBreak() {
        rpc.callSync("interrupt_break", Map.of());
    }

    @Override
    public CacheFile getNavigationArchive() {
        Map<String, Object> r = rpc.callSync("get_navigation_archive", Map.of());
        Object data = r.get("data");
        byte[] bytes = data instanceof byte[] b ? b : new byte[0];
        return new CacheFile(bytes, getInt(r, "size"));
    }

    @Override
    public boolean getAutoLogin() {
        Map<String, Object> r = rpc.callSync("get_auto_login", Map.of());
        return getBool(r, "enabled");
    }

    @Override
    public void setAutoLogin(boolean enabled) {
        rpc.callSync("set_auto_login", Map.of("enabled", enabled));
    }

    @Override
    public CacheFile takeScreenshot() {
        Map<String, Object> r = rpc.callSync("take_screenshot", Map.of());
        Object data = r.get("data");
        byte[] bytes = data instanceof byte[] b ? b : new byte[0];
        return new CacheFile(bytes, getInt(r, "size"));
    }

    // ========================== Streaming ==========================

    @Override
    public StreamInfo startStream(int frameSkip, int quality, int width, int height) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (frameSkip > 0) params.put("frame_skip", frameSkip);
        if (quality > 0) params.put("quality", quality);
        if (width > 0) params.put("width", width);
        if (height > 0) params.put("height", height);
        Map<String, Object> r = rpc.callSync("start_stream", params);
        return new StreamInfo(
                getString(r, "pipe_name"), getInt(r, "frame_skip"),
                getInt(r, "quality"), getInt(r, "width"), getInt(r, "height")
        );
    }

    @Override
    public void stopStream() {
        rpc.callSync("stop_stream", Map.of());
    }

    // ========================== Humanization ==========================

    @Override
    public boolean getHumanizationEnabled() {
        Map<String, Object> r = rpc.callSync("get_humanization_enabled", Map.of());
        return getBool(r, "enabled");
    }

    @Override
    public void setHumanizationEnabled(boolean enabled) {
        rpc.callSync("set_humanization_enabled", Map.of("enabled", enabled));
    }

    @Override
    public Personality getPersonality() {
        Map<String, Object> r;
        try {
            r = rpc.callSync("get_personality", Map.of());
        } catch (Exception e) {
            return null;
        }
        if (r == null || r.containsKey("error")) return null;

        Map<String, Object> sp = getObjectMap(r, "speed");
        Map<String, Object> pa = getObjectMap(r, "path");
        Map<String, Object> pr = getObjectMap(r, "precision");
        Map<String, Object> tr = getObjectMap(r, "tremor");
        Map<String, Object> ti = getObjectMap(r, "timing");
        Map<String, Object> fa = getObjectMap(r, "fatigue");
        Map<String, Object> ca = getObjectMap(r, "camera");
        Map<String, Object> se = getObjectMap(r, "session");

        return new Personality(
                getLong(r, "personality_id"),
                new Personality.Speed(getDouble(sp, "bias"), getDouble(sp, "consistency")),
                new Personality.Path(getDouble(pa, "curvature_bias"), getString(pa, "handedness"), getDouble(pa, "variability")),
                new Personality.Precision(getDouble(pr, "overshoot_tendency"), getDouble(pr, "correction_speed"), getDouble(pr, "target_precision")),
                new Personality.Tremor(getDouble(tr, "frequency_bias"), getDouble(tr, "amplitude_bias")),
                new Personality.Timing(getDouble(ti, "reaction_speed"), getDouble(ti, "rhythm_consistency"), getDouble(ti, "pause_tendency")),
                new Personality.Fatigue(getDouble(fa, "resistance"), getDouble(fa, "recovery")),
                new Personality.Camera(getDouble(ca, "sensitivity"), getDouble(ca, "smoothness"), getDouble(ca, "overshoot_tendency"),
                        getDouble(ca, "idle_drift_amount"), getDouble(ca, "settling_speed")),
                getDouble(r, "daily_variance"),
                new Personality.Session(getDouble(se, "fatigue_level"), getDouble(se, "attention_level"), getDouble(se, "cumulative_risk"),
                        getDouble(se, "ban_probability"), getString(se, "risk_level"), getDouble(se, "session_duration_hours"),
                        getInt(se, "total_actions"), getInt(se, "total_errors"), getInt(se, "breaks_taken"))
        );
    }

    // ========================== Inventory & Items ==========================

    @Override
    public List<InventoryInfo> queryInventories() {
        return rpc.callSyncList("query_inventories", Map.of()).stream()
                .map(m -> new InventoryInfo(getInt(m, "inventory_id"), getInt(m, "item_count"), getInt(m, "capacity")))
                .toList();
    }

    @Override
    public List<InventoryItem> queryInventoryItems(InventoryFilter filter) {
        return rpc.callSyncList("query_inventory_items", filter.toParams()).stream()
                .map(m -> new InventoryItem(getInt(m, "handle"), getInt(m, "item_id"), getInt(m, "quantity"), getInt(m, "slot")))
                .toList();
    }

    @Override
    public InventoryItem getInventoryItem(int inventoryId, int slot) {
        Map<String, Object> r = rpc.callSync("get_inventory_item",
                Map.of("inventory_id", inventoryId, "slot", slot));
        return new InventoryItem(getInt(r, "handle"), getInt(r, "item_id"), getInt(r, "quantity"), getInt(r, "slot"));
    }

    @Override
    public List<ItemVar> getItemVars(int inventoryId, int slot) {
        return rpc.callSyncList("get_item_vars", Map.of("inventory_id", inventoryId, "slot", slot)).stream()
                .map(m -> new ItemVar(getInt(m, "var_id"), getInt(m, "value")))
                .toList();
    }

    @Override
    public int getItemVarValue(int inventoryId, int slot, int varId) {
        Map<String, Object> r = rpc.callSync("get_item_var_value",
                Map.of("inventory_id", inventoryId, "slot", slot, "var_id", varId));
        return getInt(r, "value");
    }

    @Override
    public boolean isInventoryItemValid(int inventoryId, int slot) {
        Map<String, Object> r = rpc.callSync("is_inventory_item_valid",
                Map.of("inventory_id", inventoryId, "slot", slot));
        return getBool(r, "valid");
    }

    // ========================== Player Stats ==========================

    @Override
    public List<PlayerStat> getPlayerStats() {
        return rpc.callSyncList("get_player_stats", Map.of()).stream()
                .map(this::mapPlayerStat).toList();
    }

    @Override
    public PlayerStat getPlayerStat(int skillId) {
        Map<String, Object> r = rpc.callSync("get_player_stat", Map.of("skill_id", skillId));
        return mapPlayerStat(r);
    }

    @Override
    public int getPlayerStatCount() {
        Map<String, Object> r = rpc.callSync("get_player_stat_count", Map.of());
        return getInt(r, "count");
    }

    // ========================== Chat ==========================

    @Override
    public List<ChatMessage> queryChatHistory(int messageType, int maxResults) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (messageType >= 0) params.put("message_type", messageType);
        if (maxResults > 0) params.put("max_results", maxResults);
        return rpc.callSyncList("query_chat_history", params).stream()
                .map(m -> new ChatMessage(getInt(m, "index"), getInt(m, "message_type"),
                        getString(m, "text"), getString(m, "player_name")))
                .toList();
    }

    @Override
    public String getChatMessageText(int index) {
        Map<String, Object> r = rpc.callSync("get_chat_message_text", Map.of("index", index));
        return getString(r, "text");
    }

    @Override
    public String getChatMessagePlayer(int index) {
        Map<String, Object> r = rpc.callSync("get_chat_message_player", Map.of("index", index));
        return getString(r, "player_name");
    }

    @Override
    public int getChatMessageType(int index) {
        Map<String, Object> r = rpc.callSync("get_chat_message_type", Map.of("index", index));
        return getInt(r, "message_type");
    }

    @Override
    public int getChatHistorySize() {
        Map<String, Object> r = rpc.callSync("get_chat_history_size", Map.of());
        return getInt(r, "size");
    }

    // ========================== Navigation & Pathfinding ==========================

    @Override
    public void walkToAsync(int x, int y) {
        rpc.callSync("walk_to", Map.of("x", x, "y", y));
    }

    @Override
    public void walkWorldPathAsync(int x, int y, int plane) {
        walkWorldPathAsync(x, y, plane, false, null);
    }

    @Override
    public void walkWorldPathAsync(int x, int y, int plane, boolean exactDestTile, WorldPathConfig config) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("x", x);
        params.put("y", y);
        if (plane != 0) params.put("plane", plane);
        if (exactDestTile) params.put("exact_dest_tile", true);
        if (config != null && config != WorldPathConfig.DEFAULT) {
            Map<String, Object> cfg = new LinkedHashMap<>();
            if (config.agilityLevel() > 1) cfg.put("agility_level", config.agilityLevel());
            if (config.maxIterations() != 500_000) cfg.put("max_iterations", config.maxIterations());
            if (!config.allowDoors()) cfg.put("allow_doors", false);
            if (!config.allowShortcuts()) cfg.put("allow_shortcuts", false);
            if (!config.allowPlaneTransitions()) cfg.put("allow_plane_transitions", false);
            if (!config.allowClimbovers()) cfg.put("allow_climbovers", false);
            if (!config.allowTransports()) cfg.put("allow_transports", false);
            if (!config.allowTeleports()) cfg.put("allow_teleports", false);
            if (config.doorCost() != 5.0f) cfg.put("door_cost", config.doorCost());
            if (config.transitionCost() != 10.0f) cfg.put("transition_cost", config.transitionCost());
            if (config.shortcutCost() != 3.0f) cfg.put("shortcut_cost", config.shortcutCost());
            if (config.climboverCost() != 3.0f) cfg.put("climbover_cost", config.climboverCost());
            if (config.transportCost() != 15.0f) cfg.put("transport_cost", config.transportCost());
            if (config.globalTeleportMinHeuristic() != 100.0f) cfg.put("global_teleport_min_heuristic", config.globalTeleportMinHeuristic());
            if (config.heuristicWeight() != 1.0f) cfg.put("heuristic_weight", config.heuristicWeight());
            if (!cfg.isEmpty()) params.put("config", cfg);
        }
        rpc.callSync("walk_world_path", params);
    }

    @Override
    public void walkCancel() {
        rpc.callSync("walk_cancel", Map.of());
    }

    @Override
    public WalkStatus getWalkStatus() {
        Map<String, Object> r = rpc.callSync("walk_status", Map.of());
        return new WalkStatus(
                getString(r, "state"),
                getInt(r, "target_x"), getInt(r, "target_y"),
                getInt(r, "current_step"), getInt(r, "total_steps"),
                getInt(r, "nav_step"), getInt(r, "total_nav_steps"),
                getBool(r, "is_walking"), getBool(r, "is_done"),
                getBool(r, "hpa_ready")
        );
    }

    @Override
    public boolean isReachable(int x, int y) {
        Map<String, Object> r = rpc.callSync("is_reachable", Map.of("x", x, "y", y));
        return getBool(r, "reachable");
    }

    @Override
    public boolean isReachable(int x, int y, int maxIterations) {
        Map<String, Object> r = rpc.callSync("is_reachable",
                Map.of("x", x, "y", y, "max_iterations", maxIterations));
        return getBool(r, "reachable");
    }

    @Override
    public PathResult findPath(int toX, int toY) {
        Map<String, Object> r = rpc.callSync("find_path", Map.of("to_x", toX, "to_y", toY));
        return mapPathResult(r);
    }

    @Override
    public PathResult findPath(int fromX, int fromY, int toX, int toY) {
        Map<String, Object> r = rpc.callSync("find_path",
                Map.of("from_x", fromX, "from_y", fromY, "to_x", toX, "to_y", toY));
        return mapPathResult(r);
    }

    @Override
    public PathResult findWorldPath(int toX, int toY) {
        Map<String, Object> r = rpc.callSync("find_world_path", Map.of("to_x", toX, "to_y", toY));
        return mapPathResult(r);
    }

    @Override
    public PathResult findWorldPath(int fromX, int fromY, int toX, int toY) {
        Map<String, Object> r = rpc.callSync("find_world_path",
                Map.of("from_x", fromX, "from_y", fromY, "to_x", toX, "to_y", toY));
        return mapPathResult(r);
    }

    @Override
    public int getRegionCacheSize() {
        Map<String, Object> r = rpc.callSync("region_cache_info", Map.of());
        return getInt(r, "cache_size");
    }

    @Override
    public void clearRegionCache() {
        rpc.callSync("region_cache_clear", Map.of());
    }

    // ========================== Navigation Links & Teleports ==========================

    @Override
    public void navAddTransport(NavTransport t) {
        rpc.callSync("nav.add_transport", transportToMap(t));
    }

    @Override
    public void navRemoveTransport(int objectId, int x, int y, int plane) {
        navRemoveLink("nav.remove_transport", objectId, x, y, plane);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<NavTransport> navListTransports() {
        Map<String, Object> r = rpc.callSync("nav.list_transports", Map.of());
        List<Map<String, Object>> list = getList(r, "transports");
        return list.stream().map(m -> new NavTransport(
                getInt(m, "object_id"), getInt(m, "x"), getInt(m, "y"), getInt(m, "plane"),
                getInt(m, "shape"), getInt(m, "rotation"), getInt(m, "option_index"),
                getInt(m, "dest_x"), getInt(m, "dest_y"), getInt(m, "dest_plane")
        )).toList();
    }

    @Override
    public void navAddDoor(NavDoor d) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("object_id", d.objectId());
        params.put("x", d.x());
        params.put("y", d.y());
        if (d.plane() != 0) params.put("plane", d.plane());
        if (d.shape() != 0) params.put("shape", d.shape());
        if (d.rotation() != 0) params.put("rotation", d.rotation());
        rpc.callSync("nav.add_door", params);
    }

    @Override
    public void navRemoveDoor(int objectId, int x, int y, int plane) {
        navRemoveLink("nav.remove_door", objectId, x, y, plane);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<NavDoor> navListDoors() {
        Map<String, Object> r = rpc.callSync("nav.list_doors", Map.of());
        List<Map<String, Object>> list = getList(r, "doors");
        return list.stream().map(m -> new NavDoor(
                getInt(m, "object_id"), getInt(m, "x"), getInt(m, "y"), getInt(m, "plane"),
                getInt(m, "shape"), getInt(m, "rotation")
        )).toList();
    }

    @Override
    public void navAddShortcut(NavShortcut s) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("object_id", s.objectId());
        params.put("x", s.x());
        params.put("y", s.y());
        if (s.plane() != 0) params.put("plane", s.plane());
        if (s.shape() != 0) params.put("shape", s.shape());
        if (s.rotation() != 0) params.put("rotation", s.rotation());
        if (s.agilityLevel() != 1) params.put("agility_level", s.agilityLevel());
        rpc.callSync("nav.add_shortcut", params);
    }

    @Override
    public void navRemoveShortcut(int objectId, int x, int y, int plane) {
        navRemoveLink("nav.remove_shortcut", objectId, x, y, plane);
    }

    @Override
    public void navAddPlaneTransition(NavPlaneTransition t) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("object_id", t.objectId());
        params.put("x", t.x());
        params.put("y", t.y());
        if (t.plane() != 0) params.put("plane", t.plane());
        if (t.shape() != 10) params.put("shape", t.shape());
        if (t.rotation() != 0) params.put("rotation", t.rotation());
        if (t.sizeX() != 1) params.put("size_x", t.sizeX());
        if (t.sizeY() != 1) params.put("size_y", t.sizeY());
        if (t.destX() >= 0) params.put("dest_x", t.destX());
        if (t.destY() >= 0) params.put("dest_y", t.destY());
        if (t.destPlane() != 0) params.put("dest_plane", t.destPlane());
        rpc.callSync("nav.add_plane_transition", params);
    }

    @Override
    public void navRemovePlaneTransition(int objectId, int x, int y, int plane) {
        navRemoveLink("nav.remove_plane_transition", objectId, x, y, plane);
    }

    @Override
    public void navAddClimbover(NavClimbover c) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("object_id", c.objectId());
        params.put("x", c.x());
        params.put("y", c.y());
        if (c.plane() != 0) params.put("plane", c.plane());
        if (c.shape() != 0) params.put("shape", c.shape());
        if (c.rotation() != 0) params.put("rotation", c.rotation());
        rpc.callSync("nav.add_climbover", params);
    }

    @Override
    public void navRemoveClimbover(int objectId, int x, int y, int plane) {
        navRemoveLink("nav.remove_climbover", objectId, x, y, plane);
    }

    @Override
    public int navLoadJson(List<NavTransport> links) {
        List<Map<String, Object>> linkMaps = links.stream().map(this::transportToMap).toList();
        Map<String, Object> r = rpc.callSync("nav.load_json", Map.of("links", linkMaps));
        return getInt(r, "added");
    }

    @Override
    public void navSaveLinks(String path) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (path != null) params.put("path", path);
        rpc.callSync("nav.save_links", params);
    }

    @Override
    public int navLoadLinks(String path) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (path != null) params.put("path", path);
        Map<String, Object> r = rpc.callSync("nav.load_links", params);
        return getInt(r, "loaded");
    }

    @Override
    public NavStats navGetStats() {
        Map<String, Object> r = rpc.callSync("nav.stats", Map.of());
        return new NavStats(
                getInt(r, "regions"), getInt(r, "doors"), getInt(r, "shortcuts"),
                getInt(r, "plane_transitions"), getInt(r, "climbovers"), getInt(r, "transports"),
                getInt(r, "teleports"), getInt(r, "teleports_builtin"), getInt(r, "teleports_script")
        );
    }

    @Override
    public int navRegisterTeleports(String json, String format) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("json", json);
        if (format != null && !format.equals("item_teleports")) params.put("format", format);
        Map<String, Object> r = rpc.callSync("nav.register_teleports", params);
        return getInt(r, "added");
    }

    @Override
    public int navClearScriptTeleports() {
        Map<String, Object> r = rpc.callSync("nav.clear_script_teleports", Map.of());
        return getInt(r, "removed");
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<NavTeleport> navListTeleports(boolean scriptOnly) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (scriptOnly) params.put("script_only", true);
        Map<String, Object> r = rpc.callSync("nav.list_teleports", params);
        List<Map<String, Object>> list = getList(r, "teleports");
        return list.stream().map(m -> new NavTeleport(
                getInt(m, "index"), getString(m, "name"), getBool(m, "global"),
                getInt(m, "dest_x"), getInt(m, "dest_y"), getInt(m, "dest_plane"),
                getDouble(m, "cost"), getDouble(m, "cost_quick"),
                getInt(m, "chain_steps"), getInt(m, "requirements"), getBool(m, "builtin")
        )).toList();
    }

    // ========================== Navigation Helpers ==========================

    private void navRemoveLink(String method, int objectId, int x, int y, int plane) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("object_id", objectId);
        params.put("x", x);
        params.put("y", y);
        if (plane != 0) params.put("plane", plane);
        rpc.callSync(method, params);
    }

    private Map<String, Object> transportToMap(NavTransport t) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("object_id", t.objectId());
        params.put("x", t.x());
        params.put("y", t.y());
        if (t.plane() != 0) params.put("plane", t.plane());
        if (t.shape() != 10) params.put("shape", t.shape());
        if (t.rotation() != 0) params.put("rotation", t.rotation());
        if (t.optionIndex() != 0) params.put("option_index", t.optionIndex());
        params.put("dest_x", t.destX());
        params.put("dest_y", t.destY());
        if (t.destPlane() != 0) params.put("dest_plane", t.destPlane());
        return params;
    }

    // ========================== Config Type Lookups ==========================

    @Override
    @SuppressWarnings("unchecked")
    public ItemType getItemType(int id) {
        Map<String, Object> r = rpc.callSync("get_item_type", Map.of("id", id));
        return new ItemType(
                getInt(r, "id"), getString(r, "name"),
                getBool(r, "members"), getBool(r, "stackable"),
                getInt(r, "shop_price"), getInt(r, "ge_buy_limit"),
                getInt(r, "category"), getInt(r, "noted_id"), getInt(r, "wearpos"),
                getBool(r, "exchangeable"),
                getStringList(r, "ground_options"), getStringList(r, "inventory_options"),
                getObjectMap(r, "params")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public NpcType getNpcType(int id) {
        Map<String, Object> r = rpc.callSync("get_npc_type", Map.of("id", id));
        return new NpcType(
                getInt(r, "id"), getString(r, "name"), getInt(r, "combat_level"),
                getBool(r, "visible"), getBool(r, "clickable"),
                getStringList(r, "options"),
                getInt(r, "varbit_id"), getInt(r, "varp_id"),
                getIntList(r, "transforms"),
                getObjectMap(r, "params")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public LocationType getLocationType(int id) {
        Map<String, Object> r = rpc.callSync("get_location_type", Map.of("id", id));
        return new LocationType(
                getInt(r, "id"), getString(r, "name"),
                getInt(r, "size_x"), getInt(r, "size_y"),
                getInt(r, "interact_type"), getInt(r, "solid_type"),
                getBool(r, "members"),
                getStringList(r, "options"),
                getInt(r, "varbit_id"), getInt(r, "varp_id"),
                getIntList(r, "transforms"),
                getInt(r, "map_sprite_id"),
                getObjectMap(r, "params")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public EnumType getEnumType(int id) {
        Map<String, Object> r = rpc.callSync("get_enum_type", Map.of("id", id));
        return new EnumType(
                getInt(r, "id"), getInt(r, "input_type_id"), getInt(r, "output_type_id"),
                getInt(r, "int_default"), getString(r, "string_default"),
                getInt(r, "entry_count"),
                getObjectMap(r, "entries")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public StructType getStructType(int id) {
        Map<String, Object> r = rpc.callSync("get_struct_type", Map.of("id", id));
        return new StructType(getInt(r, "id"), getObjectMap(r, "params"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public SequenceType getSequenceType(int id) {
        Map<String, Object> r = rpc.callSync("get_sequence_type", Map.of("id", id));
        return new SequenceType(
                getInt(r, "id"), getInt(r, "frame_count"),
                getIntList(r, "frame_lengths"),
                getInt(r, "loop_offset"), getInt(r, "priority"),
                getInt(r, "off_hand"), getInt(r, "main_hand"),
                getInt(r, "max_loops"),
                getInt(r, "animating_precedence"), getInt(r, "walking_precedence"),
                getInt(r, "replay_mode"), getBool(r, "tweened"),
                getObjectMap(r, "params")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public QuestType getQuestType(int id) {
        Map<String, Object> r = rpc.callSync("get_quest_type", Map.of("id", id));
        return new QuestType(
                getInt(r, "id"), getString(r, "name"), getString(r, "list_name"),
                getInt(r, "category"), getInt(r, "difficulty"),
                getBool(r, "members_only"),
                getInt(r, "quest_points"), getInt(r, "quest_point_req"),
                getInt(r, "quest_item_sprite"),
                getIntList(r, "start_locations"),
                getInt(r, "alternate_start_location"),
                getIntList(r, "dependent_quest_ids"),
                getMapList(r, "skill_requirements"),
                getMapList(r, "progress_varps"),
                getMapList(r, "progress_varbits"),
                getObjectMap(r, "params")
        );
    }

    // ========================== Helpers ==========================

    private Entity mapEntity(Map<String, Object> m) {
        return new Entity(
                getInt(m, "handle"), getInt(m, "server_index"), getInt(m, "type_id"),
                getInt(m, "tile_x"), getInt(m, "tile_y"), getInt(m, "tile_z"),
                getString(m, "name"), getInt(m, "name_hash"),
                getBool(m, "is_moving"), getBool(m, "is_hidden")
        );
    }

    private Component mapComponent(Map<String, Object> m) {
        return new Component(
                getInt(m, "handle"), getInt(m, "interface_id"), getInt(m, "component_id"),
                getInt(m, "sub_component_id"), getInt(m, "type"),
                getInt(m, "item_id"), getInt(m, "item_count"), getInt(m, "sprite_id")
        );
    }

    private PlayerStat mapPlayerStat(Map<String, Object> m) {
        return new PlayerStat(
                getInt(m, "skill_id"), getInt(m, "level"), getInt(m, "boosted_level"),
                getInt(m, "max_level"), getInt(m, "xp")
        );
    }

    private World mapWorld(Map<String, Object> m) {
        return new World(
                getInt(m, "world_id"), getInt(m, "properties"),
                getInt(m, "population"), getInt(m, "ping"),
                getString(m, "activity")
        );
    }

    @SuppressWarnings("unchecked")
    private PathResult mapPathResult(Map<String, Object> r) {
        boolean found = getBool(r, "found");
        int pathLength = getInt(r, "path_length");
        List<Map<String, Object>> rawPath = getList(r, "path");
        List<int[]> path = rawPath.stream()
                .map(p -> new int[]{getInt(p, "x"), getInt(p, "y")})
                .toList();
        return new PathResult(found, pathLength, path);
    }

    @SuppressWarnings("unchecked")
    private GroundItemStack mapGroundItemStack(Map<String, Object> m) {
        List<Map<String, Object>> rawItems = getList(m, "items");
        List<GroundItem> items = rawItems.stream()
                .map(i -> new GroundItem(getInt(i, "item_id"), getInt(i, "quantity")))
                .toList();
        return new GroundItemStack(
                getInt(m, "handle"), getInt(m, "tile_x"), getInt(m, "tile_y"), getInt(m, "tile_z"), items
        );
    }

}
