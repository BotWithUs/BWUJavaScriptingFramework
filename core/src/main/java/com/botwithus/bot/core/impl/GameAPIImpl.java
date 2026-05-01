package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.ActionEntry;
import Component;
import com.botwithus.bot.api.model.EnumType;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.api.model.LoginState;
import com.botwithus.bot.api.model.NavClimbover;
import com.botwithus.bot.api.model.NavDoor;
import com.botwithus.bot.api.model.NavPlaneTransition;
import com.botwithus.bot.api.model.NavShortcut;
import com.botwithus.bot.api.model.NavStats;
import com.botwithus.bot.api.model.NavTeleport;
import com.botwithus.bot.api.model.NavTransport;
import com.botwithus.bot.api.model.NpcType;
import com.botwithus.bot.api.model.PathResult;
import com.botwithus.bot.api.model.QuestType;
import com.botwithus.bot.api.model.ScriptResult;
import com.botwithus.bot.api.model.SequenceType;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.api.model.WalkStatus;
import com.botwithus.bot.api.model.WorldPathConfig;
import com.botwithus.bot.core.rpc.RpcClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.botwithus.bot.core.impl.MapHelper.getBool;
import static com.botwithus.bot.core.impl.MapHelper.getDouble;
import static com.botwithus.bot.core.impl.MapHelper.getInt;
import static com.botwithus.bot.core.impl.MapHelper.getIntList;
import static com.botwithus.bot.core.impl.MapHelper.getList;
import static com.botwithus.bot.core.impl.MapHelper.getLong;
import static com.botwithus.bot.core.impl.MapHelper.getMapList;
import static com.botwithus.bot.core.impl.MapHelper.getObjectMap;
import static com.botwithus.bot.core.impl.MapHelper.getString;
import static com.botwithus.bot.core.impl.MapHelper.getStringList;

public class GameAPIImpl implements GameAPI {

    private final RpcClient rpc;

    public GameAPIImpl(RpcClient rpc) {
        this.rpc = rpc;
    }

    // ---------------------------------------------------------------- System

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
    public int getClientCount() {
        Map<String, Object> r = rpc.callSync("rpc.client_count", Map.of());
        return getInt(r, "count");
    }

    // ---------------------------------------------------------------- Actions

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
    public boolean areActionsBlocked() {
        Map<String, Object> r = rpc.callSync("are_actions_blocked", Map.of());
        return getBool(r, "blocked");
    }

    @Override
    public void setActionsBlocked(boolean blocked) {
        rpc.callSync("set_actions_blocked", Map.of("blocked", blocked));
    }

    // ---------------------------------------------------------------- Script execution

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

    // ---------------------------------------------------------------- Interface tree walk

    @Override
    public Component getComponent(int interfaceId, int componentId) {
        Map<String, Object> r = rpc.callSync("get_component",
                Map.of("iface", interfaceId, "comp", componentId));
        // Producer signals "not found" by writing iface=-1 in an otherwise
        // populated map; map to null on the consumer side.
        int iface = getInt(r, "iface");
        if (iface < 0) return null;
        return new Component(
                iface,
                getInt(r, "comp"),
                getInt(r, "sub"),
                getInt(r, "type"),
                getInt(r, "x"),
                getInt(r, "y"),
                getInt(r, "w"),
                getInt(r, "h"),
                getInt(r, "raw_x"),
                getInt(r, "raw_y"),
                getInt(r, "raw_w"),
                getInt(r, "raw_h"),
                getInt(r, "x_pos_mode"),
                getInt(r, "y_pos_mode"),
                getInt(r, "x_size_mode"),
                getInt(r, "y_size_mode"),
                getInt(r, "abs_screen_pos"));
    }

    @Override
    public List<Integer> getStaticChildren(int interfaceId, int componentId) {
        Map<String, Object> r = rpc.callSync("get_static_children",
                Map.of("iface", interfaceId, "comp", componentId));
        return getIntList(r, "children");
    }

    @Override
    public List<Integer> getDynamicChildren(int interfaceId, int componentId) {
        Map<String, Object> r = rpc.callSync("get_dynamic_children",
                Map.of("iface", interfaceId, "comp", componentId));
        return getIntList(r, "children");
    }

    // ---------------------------------------------------------------- State probes

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

    // ---------------------------------------------------------------- Login / breaks (mutations)

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
    public boolean getAutoLogin() {
        Map<String, Object> r = rpc.callSync("get_auto_login", Map.of());
        return getBool(r, "enabled");
    }

    @Override
    public void setAutoLogin(boolean enabled) {
        rpc.callSync("set_auto_login", Map.of("enabled", enabled));
    }

    // ---------------------------------------------------------------- Walker / pathfinder

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

    // ---------------------------------------------------------------- Nav graph CRUD

    @Override
    public void navAddTransport(NavTransport t) {
        rpc.callSync("nav.add_transport", transportToMap(t));
    }

    @Override
    public void navRemoveTransport(int objectId, int x, int y, int plane) {
        navRemoveLink("nav.remove_transport", objectId, x, y, plane);
    }

    @Override
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

    // ---------------------------------------------------------------- Config-type lookups (slice 5 stubs)

    @Override
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
    public StructType getStructType(int id) {
        Map<String, Object> r = rpc.callSync("get_struct_type", Map.of("id", id));
        return new StructType(getInt(r, "id"), getObjectMap(r, "params"));
    }

    @Override
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

    // ---------------------------------------------------------------- Helpers

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
}
