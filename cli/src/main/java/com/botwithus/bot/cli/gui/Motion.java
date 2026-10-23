package com.botwithus.bot.cli.gui;

import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;

import imgui.ImGui;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * Animation for immediate-mode widgets: hover and toggle fades, slides and the
 * live-indicator pulse. One instance belongs to the UI (see {@link Controls#motion()});
 * render thread only.
 *
 * <p>Fades store one float per string key and ease it toward a target by the
 * frame's delta time, so a transition does not snap with ImGui's stateless
 * rendering. While the user asks for reduced motion, everything snaps instead:
 * a fade lands on its target, a slide is either out or in, and a pulse holds
 * still.</p>
 */
public final class Motion {

    /**
     * Where the animations read time.
     *
     * @param deltaSeconds how long the last frame took
     * @param seconds      a clock in seconds that only moves forward
     */
    public record FrameClock(DoubleSupplier deltaSeconds, DoubleSupplier seconds) {

        public FrameClock {
            Objects.requireNonNull(deltaSeconds, "deltaSeconds");
            Objects.requireNonNull(seconds, "seconds");
        }

        /** ImGui's frame delta and time; needs a live ImGui context when read. */
        public static FrameClock imGui() {
            return new FrameClock(() -> ImGui.getIO().getDeltaTime(), ImGui::getTime);
        }
    }

    /** Speed of {@link #hover}'s fade: higher is snappier. */
    private static final float HOVER_SPEED = 12f;
    /** The level a pulse holds at while motion is reduced: halfway, its average. */
    private static final float STILL_PULSE = 0.5f;
    private static final float HALF = 0.5f;
    private static final double TURN_RADIANS = Math.PI * 2.0;

    private final BooleanSupplier isReduced;
    private final FrameClock clock;
    private final Map<String, Float> state = new HashMap<>();

    /**
     * @param isReduced asked on every call, so a change to it applies from the next frame
     * @param clock     where frame time comes from
     */
    public Motion(BooleanSupplier isReduced, FrameClock clock) {
        this.isReduced = Objects.requireNonNull(isReduced, "isReduced");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Follows the "Reduce motion" setting, read live from {@code settings}. */
    public static Motion following(HostSettings settings, FrameClock clock) {
        Objects.requireNonNull(settings, "settings");
        return new Motion(() -> settings.get(SettingKeys.REDUCE_MOTION), clock);
    }

    /** Never reduced: for tools with no settings, such as the preview renderer. */
    public static Motion full(FrameClock clock) {
        return new Motion(() -> false, clock);
    }

    /** Whether animations snap rather than move. */
    public boolean isReduced() {
        return isReduced.getAsBoolean();
    }

    /**
     * Eases the stored value for {@code key} toward {@code target} and returns the
     * new value; with reduced motion, returns {@code target}. Speed controls the
     * half-life: higher is snappier.
     */
    public float step(String key, float target, float speed) {
        if (isReduced()) {
            state.put(key, target);
            return target;
        }
        float dt = (float) clock.deltaSeconds().getAsDouble();
        float current = state.getOrDefault(key, target);
        float t = Math.min(1f, dt * speed);
        float next = current + (target - current) * t;
        state.put(key, next);
        return next;
    }

    /** Eases toward 1 when hovered, otherwise toward 0. The standard hover fade. */
    public float hover(String key, boolean hovered) {
        return step(key, hovered ? 1f : 0f, HOVER_SPEED);
    }

    /** A 0..1 sine pulse over time, for live indicators; holds still with reduced motion. */
    public float pulse(double hz) {
        if (isReduced()) {
            return STILL_PULSE;
        }
        return HALF + HALF * (float) Math.sin(clock.seconds().getAsDouble() * TURN_RADIANS * hz);
    }

    /**
     * Ease-out cubic of a 0..1 progress value, for a slide or fade in. With reduced
     * motion it is 0 until the transition starts and 1 from then on.
     */
    public float ease(float progress) {
        if (isReduced()) {
            return progress > 0f ? 1f : 0f;
        }
        float inv = 1f - progress;
        return 1f - inv * inv * inv;
    }

    /**
     * Moves a 0..1 transition one frame toward open or closed and returns where it
     * is now; with reduced motion it jumps to the end at once.
     *
     * @param progress  where the transition is, 0 closed and 1 open
     * @param isOpening whether it moves toward open
     * @param durationS how long a whole transition takes, in seconds
     */
    public float advance(float progress, boolean isOpening, float durationS) {
        if (isReduced()) {
            return isOpening ? 1f : 0f;
        }
        float step = (float) clock.deltaSeconds().getAsDouble() / durationS;
        return Math.max(0f, Math.min(1f, progress + (isOpening ? step : -step)));
    }
}
