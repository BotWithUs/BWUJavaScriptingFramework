package com.botwithus.bot.api.buff;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One buff, read once.
 *
 * @param buff      which buff
 * @param isActive  whether the buff is on: its varbit is above zero
 * @param remaining time left, for a timed buff that is active; empty otherwise
 * @param potency   strength, for a buff that has one (incense); empty otherwise
 * @param rawValue  the varbit's value, or {@code -1} when it could not be read
 */
public record BuffState(Buff buff, boolean isActive, Optional<Duration> remaining,
                        OptionalInt potency, int rawValue) {
}
