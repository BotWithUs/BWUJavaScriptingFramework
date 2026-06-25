package com.botwithus.bot.skilling.atlas;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A cache {@code struct} resolved from the Atlas by gameval name — its parameter
 * map ({@code paramId -> value}). Used to read data baked into struct params, e.g.
 * the wood-box capacity steps ({@code SKILLGUIDE_WOODCUTTING_WOOD_BOX_CAPACITY_*},
 * whose param {@code 2212} is the Woodcutting level each +10 step unlocks).
 *
 * @param id     the struct id
 * @param params parameter values keyed by param id (numbers stored as {@link Long},
 *               text as {@link String})
 */
public record Struct(int id, Map<Integer, Object> params) {

    public Struct {
        params = Map.copyOf(params);
    }

    /** The integer value of a param, or empty when absent / not numeric. */
    public OptionalInt paramInt(int paramId) {
        Object v = params.get(paramId);
        if (v instanceof Number n) {
            return OptionalInt.of(n.intValue());
        }
        if (v instanceof String s) {
            try {
                return OptionalInt.of(Integer.parseInt(s.trim()));
            } catch (NumberFormatException e) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.empty();
    }

    /** The text value of a param, or empty when absent. */
    public Optional<String> paramText(int paramId) {
        Object v = params.get(paramId);
        return v == null ? Optional.empty() : Optional.of(String.valueOf(v));
    }
}
