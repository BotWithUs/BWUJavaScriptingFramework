package com.botwithus.bot.api.gameval;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A namespace of gameval symbolic names — one per table the game ships in cache
 * index 67. A gameval name is only unique within its type, so every lookup names
 * both ({@code ITEM} + {@code "YEW_LOGS"} → 1515).
 *
 * <p>The {@link #wire()} string is the {@code etype} key stored in the index and
 * is the shared vocabulary with the offline tooling — it matches the group file
 * names under {@code gameval_beta_out/} and the {@code --etype} argument of the
 * Atlas query CLI. Two names differ between the game's vocabulary and the host's,
 * and the wire string keeps the game's: {@link #ITEM} is the game's {@code obj}
 * table, and {@link #VARP} is its {@code var_player} table.</p>
 *
 * <p>{@link #LOC} is scenery — the same entities the {@code SceneObjects} facade
 * yields. It keeps the cache's spelling so a name resolved here and a name looked
 * up in the offline tooling are unambiguously the same thing.</p>
 *
 * @see GamevalIndex
 * @see com.botwithus.bot.api.entities.SceneObjects
 */
public enum GamevalType {

    // --- entities and definitions -------------------------------------------
    /** Items. The game's {@code obj} table. */
    ITEM("item"),
    /** NPCs. */
    NPC("npc"),
    /** Scenery. Yielded by {@code api.objects()}. */
    LOC("loc"),
    /** Inventories / containers. */
    INV("inv"),
    /** Enums (id → value tables). */
    ENUM("enum"),
    /** Structs (param bags). */
    STRUCT("struct"),
    /** Params (struct/def attribute keys). */
    PARAM("param"),
    /** Animation sequences. */
    SEQ("seq"),
    /** Spot animations / graphical effects. */
    GRAPHIC("graphic"),
    /** Quests. */
    QUEST("quest"),
    /** Item/equipment categories. */
    CATEGORY("category"),
    /** Achievements. */
    ACHIEVEMENT("achievement"),
    /** Database rows. */
    DBROW("dbrow"),
    /** Database tables. */
    DBTABLE("dbtable"),
    /** World-map elements. */
    MAPELEMENT("mapelement"),

    // --- interface / UI ------------------------------------------------------
    /** Interfaces. */
    INTERFACE("interface"),
    /**
     * Interface components. Ids are packed as
     * {@code (interfaceId << 16) | componentId} — use
     * {@link GamevalIndex#component(String)} to get them split.
     */
    COMPONENT("component"),
    /** Mouse cursors. */
    CURSOR("cursor"),
    /** Overhead health bars. */
    HEADBAR("headbar"),
    /** Hit splats. */
    HITMARK("hitmark"),
    /** Interface style sheets. */
    STYLESHEET("stylesheet"),
    /** Font metrics. */
    FONTMETRICS("fontmetrics"),
    /** Interface animations. */
    UI_ANIM("ui_anim"),
    /** Interface animation curves. */
    UI_ANIM_CURVE("ui_anim_curve"),

    // --- variables -----------------------------------------------------------
    /** Player variables. The game's {@code var_player} table. */
    VARP("varp"),
    /** Player variable bits. */
    VARBIT("varbit"),
    /** Client variables. */
    VAR_CLIENT("var_client"),
    /** NPC variables. */
    VAR_NPC("var_npc"),
    /** NPC variable bits. */
    VARBIT_NPC("varbit_npc"),
    /** Scenery variables. */
    VAR_OBJECT("var_object"),
    /** Scenery variable bits. */
    VARBIT_OBJECT("varbit_object"),
    /** Clan variables. */
    VAR_CLAN("var_clan"),
    /** Clan variable bits. */
    VARBIT_CLAN("varbit_clan"),
    /** Clan-setting variables. */
    VAR_CLAN_SETTING("var_clan_setting"),
    /** Clan-setting variable bits. */
    VARBIT_CLAN_SETTING("varbit_clan_setting"),
    /** Player-group variables. */
    VAR_PLAYER_GROUP("var_player_group"),

    // --- assets --------------------------------------------------------------
    /** Models. */
    MODEL("model"),
    /** Materials / textures. */
    MATERIAL("material"),
    /** Sound effects. */
    SOUND("sound"),
    /** Music tracks. */
    MIDI("midi"),
    /** Base animation sets. */
    BAS("bas");

    private static final Map<String, GamevalType> BY_WIRE = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(GamevalType::wire, Function.identity()));

    private final String wire;

    GamevalType(String wire) {
        this.wire = wire;
    }

    /** The {@code etype} key this type is stored under in the index. */
    public String wire() {
        return wire;
    }

    /** The type for a wire {@code etype} key, or empty when unknown. */
    public static Optional<GamevalType> fromWire(String wire) {
        return Optional.ofNullable(BY_WIRE.get(wire));
    }
}
