package com.botwithus.bot.api.buff;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalRef;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Whether a {@link Buff} is on and how long it has left, from the varbits the game keeps it
 * in. Obtained from {@link GameAPI#buffs()}. Each read is one varbit read (two for incense).
 *
 * <p>The time left is the varbit times {@link #TIME_UNIT}, the unit xapi used for these
 * varbits; it has not been checked against the game's own buff bar here.</p>
 */
public final class Buffs {

    /** What one step of a timed buff's varbit is worth. */
    public static final Duration TIME_UNIT = Duration.ofSeconds(15);

    /** {@link BuffState#rawValue()} when the varbit could not be read. */
    public static final int UNREAD = -1;

    private final GameAPI api;

    public Buffs(GameAPI api) {
        this.api = api;
    }

    /** The buff's state now. */
    public BuffState state(Buff buff) {
        int value = read(buff.varbit());
        boolean isActive = value > 0;
        Optional<Duration> remaining = isActive && buff.isTimed()
                ? Optional.of(TIME_UNIT.multipliedBy(value)) : Optional.empty();
        OptionalInt potency = buff.potencyVarbit()
                .map(ref -> OptionalInt.of(read(ref)))
                .orElse(OptionalInt.empty());
        return new BuffState(buff, isActive, remaining, potency, value);
    }

    /** True when the buff is on. */
    public boolean isActive(Buff buff) {
        return read(buff.varbit()) > 0;
    }

    /** Time left on a timed buff that is on; empty when it is off or only a flag. */
    public Optional<Duration> remaining(Buff buff) {
        return state(buff).remaining();
    }

    /** Every buff that is on now. One varbit read per buff. */
    public List<Buff> active() {
        List<Buff> on = new ArrayList<>();
        for (Buff buff : Buff.values()) {
            if (isActive(buff)) {
                on.add(buff);
            }
        }
        return on;
    }

    private int read(GamevalRef varbit) {
        return api.getVarbit(varbit.resolve(api.gamevals()));
    }
}
