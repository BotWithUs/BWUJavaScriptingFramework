package com.botwithus.bot.api.event;

/**
 * Fired when a head bar (the health or adrenaline bar over an entity) changes.
 *
 * <p>Decoded from wire event {@code 71}. As with {@link HitmarkEvent}, an agent build that
 * does not emit this type produces none; poll {@code GameAPI.getHeadbars} for the bars an
 * entity shows right now.</p>
 *
 * @param targetServerIndex server index of the entity the bar is on
 * @param targetType        {@link HitmarkEvent#TARGET_PLAYER} or {@link HitmarkEvent#TARGET_NPC}
 * @param headbarType       the head bar type id ({@code Headbar.HEALTH_DEFAULT},
 *                          {@code Headbar.ADRENALINE_DEFAULT}, ...)
 * @param value             the bar fill, {@code 0..Headbar.MAX_VALUE}
 * @param timestamp         event creation time in milliseconds since epoch
 */
public record HeadbarEvent(int targetServerIndex, int targetType, int headbarType, int value,
                           long timestamp) implements GameEvent {

    public HeadbarEvent(int targetServerIndex, int targetType, int headbarType, int value) {
        this(targetServerIndex, targetType, headbarType, value, System.currentTimeMillis());
    }
}
