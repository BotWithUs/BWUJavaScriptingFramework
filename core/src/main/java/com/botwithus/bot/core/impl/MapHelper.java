package com.botwithus.bot.core.impl;

import java.util.List;
import java.util.Map;

/**
 * Shared type-safe extraction helpers for reading values from
 * {@code Map<String, Object>} structures returned by MessagePack/RPC.
 *
 * <p>Used by {@link GameAPIImpl} and {@link EventDispatcher} to avoid
 * duplicating the same null-safe type coercion logic.</p>
 */
public final class MapHelper {

    private MapHelper() {}

    public static int getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    public static long getLong(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    public static float getFloat(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number n ? n.floatValue() : 0f;
    }

    public static double getDouble(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    public static String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    public static boolean getBool(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Boolean b && b;
    }

    public static float[] getFloatArray(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            float[] arr = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                arr[i] = item instanceof Number n ? n.floatValue() : 0f;
            }
            return arr;
        }
        return new float[0];
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) return (List<Map<String, Object>>) list;
        return List.of();
    }

    public static List<String> getStringList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            return list.stream().map(o -> o != null ? o.toString() : "").toList();
        }
        return List.of();
    }

    public static List<Integer> getIntList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            return list.stream()
                    .map(o -> o instanceof Number n ? n.intValue() : 0)
                    .toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getObjectMap(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getMapList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            return list.stream()
                    .filter(o -> o instanceof Map)
                    .map(o -> (Map<String, Object>) o)
                    .toList();
        }
        return List.of();
    }

    /**
     * Gets a string value, returning {@code null} instead of empty string when absent.
     * Used by EventDispatcher for nullable string fields.
     */
    public static String getStringNullable(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }
}
