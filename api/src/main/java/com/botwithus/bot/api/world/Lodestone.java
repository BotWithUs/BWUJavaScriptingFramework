package com.botwithus.bot.api.world;

import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.util.Interfaces;

/**
 * A lodestone on the Lodestone Network ({@code THAS_LODESTONE_MAP}).
 *
 * <p>Each constant names two gameval entries: the destination button on the network map,
 * and the varbit that says the lodestone is unlocked. Most lodestones have their own
 * {@code THAS_ACTIVE_*} flag; four are gated on other state instead, with the threshold
 * below (quest progress for Lunar Isle and the Bandit Camp, a build tier for Anachronia and
 * Fort Forinthry). The ids after each name are the current values, used only when no
 * gameval index is deployed.</p>
 */
public enum Lodestone {

    AL_KHARID("AL_KHARID", 10, "THAS_ACTIVE_AL_KHARID", 28),
    ANACHRONIA("DINO_ISLAND", 24, "DINO_BASE_CAMP_BUILDING_TIER_LODESTONE", 44270),
    ARDOUGNE("ARDOUGNE", 11, "THAS_ACTIVE_ARDOUGNE", 29),
    ASHDALE("ASHDALE", 33, "THAS_ACTIVE_ASHDALE", 22430),
    BANDIT_CAMP("ANCIENTS", 8, "DESERTTREASURE", 9482, 15),
    BURTHORPE("BURTHORPE", 12, "THAS_ACTIVE_BURTHORPE", 30),
    CANIFIS("CANIFIS", 26, "THAS_ACTIVE_CANIFIS", 18523),
    CATHERBY("CATHERBY", 13, "THAS_ACTIVE_CATHERBY", 31),
    CITY_OF_UM("CITYOFUM", 35, "THAS_ACTIVE_CITYOFUM", 53270),
    DRAYNOR_VILLAGE("DRAYNOR", 14, "THAS_ACTIVE_DRAYNOR", 32),
    EAGLES_PEAK("EAGLES", 27, "THAS_ACTIVE_EAGLES", 18524),
    EDGEVILLE("EDGEVILLE", 15, "THAS_ACTIVE_EDGEVILLE", 33),
    FALADOR("FALADOR", 16, "THAS_ACTIVE_FALADOR", 34),
    FORT_FORINTHRY("FORT", 22, "PLAYER_FORT_LODESTONE", 52518),
    FREMENNIK_PROVINCE("FREMENNIK", 28, "THAS_ACTIVE_FREMENNIK", 18525),
    KARAMJA("KARAMJA", 29, "THAS_ACTIVE_KARAMJA", 18526),
    LUMBRIDGE("LUMBRIDGE", 17, "THAS_ACTIVE_LUMBRIDGE", 35),
    LUNAR_ISLE("LUNAR", 9, "LUNAR_QUEST_MAIN", 10236, 100),
    MENAPHOS("MENAPHOS", 23, "THAS_ACTIVE_MENAPHOS", 36173),
    OOGLOG("OOGLOG", 30, "THAS_ACTIVE_OOGLOG", 18527),
    PORT_SARIM("PORT_SARIM", 18, "THAS_ACTIVE_PORT_SARIM", 36),
    PRIFDDINAS("PRIFDDINAS", 34, "THAS_ACTIVE_PRIFDDINAS", 24967),
    SEERS_VILLAGE("SEERS", 19, "THAS_ACTIVE_SEERS", 37),
    TAVERLEY("TAVERLEY", 20, "THAS_ACTIVE_TAVERLEY", 38),
    TIRANNWN("TIRRANWN", 31, "THAS_ACTIVE_TIRRANWN", 18528),
    VARROCK("VARROCK", 21, "THAS_ACTIVE_VARROCK", 39),
    WENDLEWICK("WENDLEWICK", 41, "THAS_ACTIVE_WENDLEWICK", 60739),
    WILDERNESS_CRATER("WILDERNESS", 32, "THAS_ACTIVE_WILDERNESS", 18529),
    YANILLE("YANILLE", 25, "THAS_ACTIVE_YANILLE", 40);

    /** {@code THAS_LODESTONE_MAP}: the Lodestone Network window. */
    public static final int NETWORK_INTERFACE = 1092;
    private static final String BUTTON_PREFIX = "THAS_LODESTONE_MAP__";
    /** An ordinary {@code THAS_ACTIVE_*} flag reads 1 once unlocked. */
    private static final int UNLOCKED = 1;

    private final GamevalRef button;
    private final GamevalRef unlockVarbit;
    private final int unlockedAtLeast;

    Lodestone(String buttonSuffix, int buttonComponent, String unlockGameval, int unlockId) {
        this(buttonSuffix, buttonComponent, unlockGameval, unlockId, UNLOCKED);
    }

    Lodestone(String buttonSuffix, int buttonComponent, String unlockGameval, int unlockId,
              int unlockedAtLeast) {
        this.button = new GamevalRef(GamevalType.COMPONENT, BUTTON_PREFIX + buttonSuffix,
                Interfaces.componentHash(NETWORK_INTERFACE, buttonComponent));
        this.unlockVarbit = new GamevalRef(GamevalType.VARBIT, unlockGameval, unlockId);
        this.unlockedAtLeast = unlockedAtLeast;
    }

    /** Its destination button on the network map (packed component hash when resolved). */
    public GamevalRef button() {
        return button;
    }

    /** The varbit that gates it. */
    public GamevalRef unlockVarbit() {
        return unlockVarbit;
    }

    /** True when {@code varbitValue}, read from {@link #unlockVarbit()}, means unlocked. */
    public boolean isUnlockedBy(int varbitValue) {
        return varbitValue >= unlockedAtLeast;
    }
}
