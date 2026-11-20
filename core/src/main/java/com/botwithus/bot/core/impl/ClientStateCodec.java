package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.model.ComponentChild;
import com.botwithus.bot.api.model.GeOffer;
import com.botwithus.bot.api.model.ScreenProjection;
import com.botwithus.bot.api.model.ScriptResult;
import com.botwithus.bot.api.model.TargetMode;
import com.botwithus.bot.api.model.WorldToScreen;
import com.botwithus.bot.api.world.Coordinate;
import com.botwithus.bot.api.world.World;
import com.botwithus.bot.api.world.WorldHopRequest;
import com.botwithus.bot.api.world.WorldList;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.botwithus.bot.core.impl.MapHelper.getBool;
import static com.botwithus.bot.core.impl.MapHelper.getInt;
import static com.botwithus.bot.core.impl.MapHelper.getIntOr;
import static com.botwithus.bot.core.impl.MapHelper.getLong;
import static com.botwithus.bot.core.impl.MapHelper.getMapList;
import static com.botwithus.bot.core.impl.MapHelper.getObjectMap;
import static com.botwithus.bot.core.impl.MapHelper.getString;

/**
 * Params and reply decoding for the client-state RPCs of NXTLibrary's PUBLIC_PROTOCOL
 * sections 6.2.1, 6.2.2, 6.7 and 6.8: {@code get_ge_offers}, {@code get_world_list},
 * {@code set_world}, {@code get_target_mode}, {@code world_to_screen},
 * {@code get_static_children} / {@code get_dynamic_children} with {@code withSub}, and the
 * {@code execute_script} argument limits.
 *
 * <p>Pure functions over the reply maps, so every shape is testable without a pipe.</p>
 */
final class ClientStateCodec {

    static final String GET_GE_OFFERS = "get_ge_offers";
    static final String GET_WORLD_LIST = "get_world_list";
    static final String GET_CURRENT_WORLD = "get_current_world";
    static final String SET_WORLD = "set_world";
    static final String GET_TARGET_MODE = "get_target_mode";
    static final String WORLD_TO_SCREEN = "world_to_screen";
    static final String GET_STATIC_CHILDREN = "get_static_children";
    static final String GET_DYNAMIC_CHILDREN = "get_dynamic_children";

    /** The main Grand Exchange market; market 1's contents are not verified. */
    static final int MAIN_MARKET = 0;
    /** {@code get_current_world} when the world is unknown. */
    static final int UNKNOWN_WORLD = -1;
    private static final int UNSET = -1;

    private ClientStateCodec() {
    }

    // ---------------------------------------------------------------- GE

    static Map<String, Object> geOffersParams() {
        return Map.of("market", MAIN_MARKET);
    }

    static List<GeOffer> decodeGeOffers(Map<String, Object> reply) {
        List<GeOffer> out = new ArrayList<>();
        for (Map<String, Object> m : getMapList(reply, "offers")) {
            out.add(new GeOffer(getInt(m, "slot"), getInt(m, "stage"), getString(m, "stageName"),
                    getInt(m, "type"), getString(m, "typeName"), getInt(m, "itemId"),
                    getInt(m, "itemCount"), getInt(m, "soldCount"), getLong(m, "price"),
                    getLong(m, "goldFromSale")));
        }
        return out;
    }

    // ---------------------------------------------------------------- Worlds

    static WorldList decodeWorldList(Map<String, Object> reply) {
        List<World> worlds = new ArrayList<>();
        for (Map<String, Object> m : getMapList(reply, "worlds")) {
            worlds.add(new World(getInt(m, "id"), getInt(m, "flags"), getString(m, "activity"),
                    getInt(m, "locationId"), getString(m, "locationName"),
                    getInt(m, "population"), getInt(m, "ping")));
        }
        return new WorldList(worlds, getInt(reply, "total"), getInt(reply, "skipped"),
                getBool(reply, "truncated"));
    }

    static int decodeCurrentWorld(Map<String, Object> reply) {
        return getIntOr(reply, "world_id", UNKNOWN_WORLD);
    }

    static Map<String, Object> setWorldParams(int worldId) {
        if (worldId <= 0) {
            throw new IllegalArgumentException("world id must be > 0, got " + worldId);
        }
        return Map.of("world_id", worldId);
    }

    /**
     * {@code started} is the predicate; {@code reason} only the message. An agent that
     * predates the reply answers {@code {}}: not started, no reason.
     */
    static WorldHopRequest decodeSetWorld(Map<String, Object> reply) {
        boolean isStarted = getBool(reply, "started");
        Object reason = reply.get("reason");
        return new WorldHopRequest(isStarted, isStarted || reason == null ? "" : reason.toString());
    }

    // ---------------------------------------------------------------- Target mode

    static TargetMode decodeTargetMode(Map<String, Object> reply) {
        if (!getBool(reply, "active")) {
            return TargetMode.INACTIVE;
        }
        Map<String, Object> src = getObjectMap(reply, "source");
        Optional<TargetMode.Source> source = src.isEmpty() ? Optional.empty()
                : Optional.of(new TargetMode.Source(getInt(src, "iface"), getInt(src, "comp"),
                        getIntOr(src, "sub", UNSET)));
        return new TargetMode(true, getBool(reply, "isItem"), getIntOr(reply, "objId", UNSET),
                source, getString(reply, "verb"), getString(reply, "name"),
                getInt(reply, "targetMask"));
    }

    // ---------------------------------------------------------------- Projection

    static Map<String, Object> worldToScreenParams(List<Coordinate> tiles) {
        if (tiles.isEmpty() || tiles.size() > WorldToScreen.MAX_POINTS) {
            throw new IllegalArgumentException("world_to_screen takes 1.." + WorldToScreen.MAX_POINTS
                    + " tiles, got " + tiles.size());
        }
        List<Map<String, Object>> points = new ArrayList<>(tiles.size());
        for (Coordinate c : tiles) {
            points.add(Map.of("x", c.x(), "y", c.y(), "plane", c.plane()));
        }
        return Map.of("points", points);
    }

    static WorldToScreen decodeWorldToScreen(Map<String, Object> reply) {
        Map<String, Object> vp = getObjectMap(reply, "viewport");
        Optional<WorldToScreen.Viewport> viewport = vp.isEmpty() ? Optional.empty()
                : Optional.of(new WorldToScreen.Viewport(getInt(vp, "x"), getInt(vp, "y"),
                        getInt(vp, "width"), getInt(vp, "height")));
        List<ScreenProjection> points = new ArrayList<>();
        for (Map<String, Object> m : getMapList(reply, "points")) {
            points.add(new ScreenProjection(ScreenProjection.Result.fromWire(getString(m, "result")),
                    getBool(m, "onScreen"), getIntOr(m, "x", ScreenProjection.NO_PIXEL),
                    getIntOr(m, "y", ScreenProjection.NO_PIXEL)));
        }
        return new WorldToScreen(viewport, points);
    }

    // ---------------------------------------------------------------- Children

    static Map<String, Object> childSlotsParams(int interfaceId, int componentId) {
        return Map.of("iface", interfaceId, "comp", componentId, "withSub", true);
    }

    static List<ComponentChild> decodeChildSlots(Map<String, Object> reply) {
        List<ComponentChild> out = new ArrayList<>();
        for (Map<String, Object> m : getMapList(reply, "children")) {
            out.add(new ComponentChild(getIntOr(m, "comp", UNSET), getIntOr(m, "sub", UNSET)));
        }
        return out;
    }

    // ---------------------------------------------------------------- execute_script

    /**
     * The {@code execute_script} params, refusing what the agent would refuse: more than
     * {@link ScriptResult#MAX_ARGS} arguments in all, or a string over
     * {@link ScriptResult#MAX_STRING_ARG_BYTES} bytes of UTF-8.
     */
    static Map<String, Object> executeScriptParams(long handle, int[] intArgs, String[] stringArgs,
                                                   String[] returns) {
        int ints = intArgs == null ? 0 : intArgs.length;
        int strings = stringArgs == null ? 0 : stringArgs.length;
        if (ints + strings > ScriptResult.MAX_ARGS) {
            throw new IllegalArgumentException("execute_script takes at most " + ScriptResult.MAX_ARGS
                    + " int and string arguments together, got " + (ints + strings));
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("handle", handle);
        if (ints > 0) {
            List<Integer> intList = new ArrayList<>(ints);
            for (int a : intArgs) {
                intList.add(a);
            }
            params.put("int_args", intList);
        }
        if (strings > 0) {
            params.put("string_args", checkedStrings(stringArgs));
        }
        if (returns != null && returns.length > 0) {
            params.put("returns", List.of(returns));
        }
        return params;
    }

    private static List<String> checkedStrings(String[] stringArgs) {
        for (int i = 0; i < stringArgs.length; i++) {
            if (stringArgs[i] == null) {
                throw new IllegalArgumentException("string argument " + i + " is null");
            }
            int bytes = stringArgs[i].getBytes(StandardCharsets.UTF_8).length;
            if (bytes > ScriptResult.MAX_STRING_ARG_BYTES) {
                throw new IllegalArgumentException("string argument " + i + " is " + bytes
                        + " bytes of UTF-8; the limit is " + ScriptResult.MAX_STRING_ARG_BYTES);
            }
        }
        return List.of(stringArgs);
    }
}
