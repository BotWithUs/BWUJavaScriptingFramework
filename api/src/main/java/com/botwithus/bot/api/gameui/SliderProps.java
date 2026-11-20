package com.botwithus.bot.api.gameui;

/**
 * Properties of a {@link Slider}.
 *
 * <p>The client sets a slider's range up in one step, so {@link #range} takes all
 * four values and there is no way to send one alone. The client's rule, which the
 * agent checks: {@code min < max}, {@code 0 < step <= max - min},
 * {@code (max - min) % step == 0} and {@code 0 < ticks <= max - min}.</p>
 */
public final class SliderProps extends UiProps<SliderProps> {

    public SliderProps() {
    }

    @Override
    SliderProps self() {
        return this;
    }

    /** The range, the step between values and the number of tick marks. Resets the value to {@code min}. */
    public SliderProps range(int min, int max, int step, int ticks) {
        put("min", min);
        put("max", max);
        put("step", step);
        return put("ticks", ticks);
    }

    /**
     * The position: {@code min <= value <= max} and {@code value % step == 0}. Applied
     * after a {@link #range} in the same call. Setting it raises no
     * {@link UiHook#ON_SELECTION_CHANGED}.
     */
    public SliderProps value(int value) {
        return put("value", value);
    }
}
