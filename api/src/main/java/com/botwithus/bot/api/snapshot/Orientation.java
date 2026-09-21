package com.botwithus.bot.api.snapshot;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Which way an entity is facing, as the client stores it.
 *
 * <p>{@link #raw()} is the client's own angle, {@code 0..16383} per full turn. It is the
 * entity's <em>rendered</em> facing, which the client interpolates while the entity turns,
 * so for a few ticks after a turn starts it reads an in-between angle rather than the
 * direction being turned towards. It is {@link #UNKNOWN_RAW} when the producer could not
 * read one.</p>
 *
 * <p>For anything but an exact comparison, use {@link #degrees()} (a compass bearing,
 * clockwise from north) or {@link #compass()} (the nearest of eight points). Both are empty
 * when the facing is unknown. They never quietly answer "north" or "0 degrees" for a value
 * that was not read.</p>
 *
 * @param raw the client angle {@code 0..16383}, or {@link #UNKNOWN_RAW}
 */
public record Orientation(int raw) {

    /** Client angle units in one full turn. */
    public static final int FULL_TURN = 16384;

    /**
     * The raw angle that faces north (+tileY). The raw angle grows clockwise seen from
     * above: {@code 0} south, {@code 4096} west, {@code 8192} north, {@code 12288} east.
     *
     * <p>This zero offset is inferred statically and not yet confirmed against a live
     * client. If that check disagrees, this constant is the one line to change, and
     * {@code OrientationTest}'s cardinal pins say what the answers must become.</p>
     */
    public static final int NORTH_RAW = 8192;

    /** {@link #raw()} when the facing is not known. */
    public static final int UNKNOWN_RAW = -1;

    /** What the producer publishes on the wire for "not known": an all-ones {@code u16}. */
    public static final int WIRE_UNKNOWN = 0xFFFF;

    private static final double DEGREES_PER_TURN = 360.0;
    private static final Orientation UNKNOWN = new Orientation(UNKNOWN_RAW);

    public Orientation {
        if (raw != UNKNOWN_RAW && (raw < 0 || raw >= FULL_TURN)) {
            throw new IllegalArgumentException(
                    "orientation must be 0.." + (FULL_TURN - 1) + " or " + UNKNOWN_RAW + ": " + raw);
        }
    }

    /**
     * Decodes the producer's zero-extended {@code u16}. {@link #WIRE_UNKNOWN} maps to an
     * unknown orientation; any other value is the angle itself.
     *
     * @throws IllegalArgumentException for a value the producer promises never to publish
     *                                  ({@code 16384..65534}), rather than wrapping it into
     *                                  a plausible wrong angle
     */
    public static Orientation fromWire(int wireValue) {
        return wireValue == WIRE_UNKNOWN ? UNKNOWN : new Orientation(wireValue);
    }

    /** An orientation that was not read. */
    public static Orientation unknown() {
        return UNKNOWN;
    }

    public boolean isKnown() {
        return raw != UNKNOWN_RAW;
    }

    /** Compass bearing in {@code [0, 360)}, clockwise from north; empty when unknown. */
    public OptionalDouble degrees() {
        if (!isKnown()) {
            return OptionalDouble.empty();
        }
        int fromNorth = Math.floorMod(raw - NORTH_RAW, FULL_TURN);
        return OptionalDouble.of(fromNorth * DEGREES_PER_TURN / FULL_TURN);
    }

    /**
     * The nearest of the eight compass points; empty when unknown. A bearing exactly on a
     * sector boundary (22.5, 67.5, ...) rounds clockwise.
     */
    public Optional<Direction> compass() {
        OptionalDouble bearing = degrees();
        if (bearing.isEmpty()) {
            return Optional.empty();
        }
        Direction[] points = Direction.values();
        int sector = (int) Math.round(bearing.getAsDouble() / Direction.SECTOR_DEGREES);
        return Optional.of(points[sector % points.length]);
    }
}
