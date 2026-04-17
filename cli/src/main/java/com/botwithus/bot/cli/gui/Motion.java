package com.botwithus.bot.cli.gui;

import imgui.ImGui;

import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight per-ID animation state for immediate-mode widgets.
 *
 * Stores a single float value per string key and lerps it toward a target
 * using the current frame delta time. Used by hover/active transitions
 * that would otherwise snap instantly with ImGui's stateless rendering.
 */
public final class Motion {

    private Motion() {}

    private static final Map<String, Float> STATE = new HashMap<>();

    /**
     * Ease the stored value for {@code key} toward {@code target} and return
     * the new value. Speed controls the half-life: higher = snappier.
     */
    public static float step(String key, float target, float speed) {
        float dt = ImGui.getIO().getDeltaTime();
        float current = STATE.getOrDefault(key, target);
        float t = Math.min(1f, dt * speed);
        float next = current + (target - current) * t;
        STATE.put(key, next);
        return next;
    }

    /** Ease toward 1 when hovered, otherwise toward 0. Standard hover fade. */
    public static float hover(String key, boolean hovered) {
        return step(key, hovered ? 1f : 0f, 12f);
    }

    /** 0..1 sine pulse driven by wall-clock time, useful for live indicators. */
    public static float pulse(double hz) {
        return 0.5f + 0.5f * (float) Math.sin(ImGui.getTime() * Math.PI * 2.0 * hz);
    }

    /** Ease-out cubic applied to a 0..1 progress value. */
    public static float easeOutCubic(float t) {
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }
}
