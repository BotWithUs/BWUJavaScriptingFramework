package com.botwithus.bot.api.combat;

import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;

import java.util.Arrays;
import java.util.List;

/** A prayer book: the standard prayers or Ancient Curses. */
public enum PrayerBook {

    /** Standard prayers ({@code PRAYER_MODE} 0). */
    NORMAL(0, new GamevalRef(GamevalType.ENUM, "COMBATV2_ENUM_OF_ALL_PRAYERS", 14185)),
    /** Ancient Curses ({@code PRAYER_MODE} 1). */
    CURSES(1, new GamevalRef(GamevalType.ENUM, "COMBATV2_ENUM_OF_ALL_CURSES", 14186)),
    /** The book could not be read. */
    UNKNOWN(-1, null);

    private final int modeValue;
    private final GamevalRef structEnum;

    PrayerBook(int modeValue, GamevalRef structEnum) {
        this.modeValue = modeValue;
        this.structEnum = structEnum;
    }

    /** The book whose {@code PRAYER_MODE} value is {@code value}, or {@link #UNKNOWN}. */
    public static PrayerBook fromModeValue(int value) {
        for (PrayerBook book : values()) {
            if (book != UNKNOWN && book.modeValue == value) {
                return book;
            }
        }
        return UNKNOWN;
    }

    /** The cache enum listing this book's prayer structs; {@code null} for {@link #UNKNOWN}. */
    public GamevalRef structEnum() {
        return structEnum;
    }

    /** This book's prayers, in the cache's order; empty for {@link #UNKNOWN}. */
    public List<Prayer> prayers() {
        return Arrays.stream(Prayer.values()).filter(p -> p.book() == this).toList();
    }
}
