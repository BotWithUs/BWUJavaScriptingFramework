package com.botwithus.bot.api.model;

/**
 * Where one tile centre lands on screen ({@code world_to_screen}), in window client pixels.
 *
 * <p><b>Predicate: {@link #onScreen()}</b>, true only for {@link Result#OK}. {@code x} and
 * {@code y} are real for {@link Result#OK} and {@link Result#OFF_VIEWPORT} and are
 * {@link #NO_PIXEL} otherwise; {@link #hasPixel()} says which.</p>
 *
 * @param result   how the projection went
 * @param onScreen true when the point is inside the scene viewport
 * @param x        client-area x, or {@link #NO_PIXEL}
 * @param y        client-area y, or {@link #NO_PIXEL}
 */
public record ScreenProjection(Result result, boolean onScreen, int x, int y) {

    /** The {@code x} / {@code y} of a projection the agent gave no pixel for. */
    public static final int NO_PIXEL = Integer.MIN_VALUE;

    /** True when {@code x} and {@code y} are real pixels. */
    public boolean hasPixel() {
        return x != NO_PIXEL && y != NO_PIXEL;
    }

    /** The agent's {@code result} string, decoded. */
    public enum Result {
        /** On screen, inside the scene viewport. */
        OK("ok"),
        /** In front of the camera but outside the viewport; the pixel is real. */
        OFF_VIEWPORT("off_viewport"),
        BEHIND_CAMERA("behind_camera"),
        /** No scene, root interface or window to project into. */
        UNAVAILABLE("unavailable"),
        /** The tile is on another plane than the local player; the agent refuses to guess. */
        WRONG_PLANE("wrong_plane"),
        /** A string this host does not list. */
        UNKNOWN("");

        private final String wireName;

        Result(String wireName) {
            this.wireName = wireName;
        }

        /** The agent's spelling; empty for {@link #UNKNOWN}. */
        public String wireName() {
            return wireName;
        }

        /** The result for an agent string; {@link #UNKNOWN} for anything unlisted. */
        public static Result fromWire(String name) {
            for (Result r : values()) {
                if (r != UNKNOWN && r.wireName.equals(name)) {
                    return r;
                }
            }
            return UNKNOWN;
        }
    }
}
