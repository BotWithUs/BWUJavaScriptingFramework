package com.botwithus.bot.api.gameval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code etype} vocabulary. These strings are the shared key between
 * three places that are built separately and can silently drift: this enum, the
 * {@code etype} column written by {@code build_gameval_db.py}, and the group
 * file names the gameval fetcher emits (one JSON per index-67 archive, with
 * {@code obj} → {@code item} and {@code var_player} → {@code varp} renamed on
 * the way in).
 *
 * <p>A name change here that isn't matched in the build script turns every
 * lookup of that type into a silent miss, which is why the whole set is
 * asserted rather than a sample.</p>
 */
class GamevalTypeTest {

    /** The 41 groups the index carries, as of the 2026-07-27 gameval snapshot. */
    private static final Set<String> EXPECTED_WIRE_NAMES = Set.of(
            "achievement", "bas", "category", "component", "cursor",
            "dbrow", "dbtable", "enum", "fontmetrics", "graphic",
            "headbar", "hitmark", "interface", "inv", "item",
            "loc", "mapelement", "material", "midi", "model",
            "npc", "param", "quest", "seq", "sound",
            "struct", "stylesheet", "ui_anim", "ui_anim_curve", "var_clan",
            "var_clan_setting", "var_client", "var_npc", "var_object", "var_player_group",
            "varbit", "varbit_clan", "varbit_clan_setting", "varbit_npc", "varbit_object",
            "varp");

    @Test
    void wireNamesMatchTheIndexVocabulary() {
        Set<String> actual = Stream.of(GamevalType.values())
                .map(GamevalType::wire)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(EXPECTED_WIRE_NAMES, actual);
    }

    @Test
    void wireNamesAreUnique() {
        List<String> all = Stream.of(GamevalType.values()).map(GamevalType::wire).toList();
        assertEquals(all.size(), Set.copyOf(all).size(), "two types share one wire name");
    }

    @Test
    void wireNamesRoundTrip() {
        for (GamevalType type : GamevalType.values()) {
            assertEquals(Optional.of(type), GamevalType.fromWire(type.wire()));
        }
        assertEquals(Optional.empty(), GamevalType.fromWire("not_a_group"));
    }

    @Test
    void renamedGroupsKeepTheGameVocabulary() {
        // The game calls these "obj" and "var_player"; the host calls them item
        // and varp. The wire string is what the index stores, so it must be the
        // host spelling on both sides — see build_gameval_db.py's GROUP_TO_ETYPE.
        assertEquals("item", GamevalType.ITEM.wire());
        assertEquals("varp", GamevalType.VARP.wire());
        assertTrue(EXPECTED_WIRE_NAMES.contains("loc"), "scenery keeps the cache spelling");
    }
}
