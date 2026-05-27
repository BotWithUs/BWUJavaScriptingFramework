package com.botwithus.bot.api.component;

import java.util.HashMap;
import java.util.Map;

/**
 * Stable, build-independent semantic category of an interface component.
 *
 * <p>The raw {@code Component.type()} byte is the game's
 * {@code jag::Component::getComponentType()} return, which drifts between game
 * builds (LAYER was 14 in one build, 19 in another). The producer classifies
 * that raw byte into one of these stable codes (its {@code WireCategory} enum)
 * and ships it as {@code Component.category()}; this enum mirrors those codes
 * one-for-one. Query on this — never on the raw byte.</p>
 *
 * <p>The numeric {@link #code()} values are a wire contract shared with the
 * producer's {@code game/Interfaces.h}: they never change, only append.</p>
 */
public enum ComponentType {

    UNKNOWN(0),
    LAYER(1),
    BOX(2),
    TEXT(3),
    SPRITE(4),
    MODEL(5),
    BUTTON(6),
    DIVIDER(7),
    LIST(8),
    INPUT(9),
    COMBO(10),
    MEDIA(11),
    TOOLTIP(12),
    CRM_VIEW(13),
    TABLE(14),
    CUTSCENE(15);

    private static final Map<Integer, ComponentType> BY_CODE = new HashMap<>();

    static {
        for (ComponentType t : values()) {
            BY_CODE.put(t.code, t);
        }
    }

    private final int code;

    ComponentType(int code) {
        this.code = code;
    }

    /** The stable wire code the producer emits for this category. */
    public int code() {
        return code;
    }

    /**
     * Resolves a wire code to its category, or {@link #UNKNOWN} for an
     * unrecognized code (e.g. a newer producer that added a category this
     * consumer doesn't know yet).
     */
    public static ComponentType fromCode(int code) {
        return BY_CODE.getOrDefault(code, UNKNOWN);
    }
}
