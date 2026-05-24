package com.botwithus.bot.api.inventory;

/**
 * Action type IDs corresponding to the game's mini menu system. Mirrors the
 * {@code DoAction} enum in {@code NXTLibrary/src/offsets/offsets.h}.
 *
 * <p>Used to build {@link com.botwithus.bot.api.model.GameAction} values for
 * {@link com.botwithus.bot.api.GameAPI#queueAction queueAction}. Most callers
 * should reach for the {@code interact(option)} helpers on the rich entity
 * wrappers (e.g. {@code Npc}, {@code SceneObject}) which look these up
 * internally; the constants are exported for direct queueing.</p>
 */
public final class ActionTypes {

    private ActionTypes() {}

    public static final int WALK = 23;

    // ---------------- NPC ----------------
    public static final int SELECT_NPC = 8;
    public static final int NPC1 = 9;
    public static final int NPC2 = 10;
    public static final int NPC3 = 11;
    public static final int NPC4 = 12;
    public static final int NPC5 = 13;
    public static final int NPC6 = 1003;
    /** NPC option action IDs indexed by 1-based option slot (slot 0 unused). */
    public static final int[] NPC_OPTIONS = { 0, NPC1, NPC2, NPC3, NPC4, NPC5, NPC6 };

    // ---------------- Scene Object ----------------
    public static final int SELECT_OBJECT = 2;
    public static final int OBJECT1 = 3;
    public static final int OBJECT2 = 4;
    public static final int OBJECT3 = 5;
    public static final int OBJECT4 = 6;
    public static final int OBJECT5 = 1001;
    public static final int OBJECT6 = 1002;
    /** Object option action IDs indexed by 1-based option slot. */
    public static final int[] OBJECT_OPTIONS = { 0, OBJECT1, OBJECT2, OBJECT3, OBJECT4, OBJECT5, OBJECT6 };

    // ---------------- Ground Item ----------------
    public static final int SELECT_GROUND_ITEM = 17;
    public static final int GROUND_ITEM1 = 18;
    public static final int GROUND_ITEM2 = 19;
    public static final int GROUND_ITEM3 = 20;
    public static final int GROUND_ITEM4 = 21;
    public static final int GROUND_ITEM5 = 22;
    public static final int GROUND_ITEM6 = 1004;
    public static final int[] GROUND_ITEM_OPTIONS = {
            0, GROUND_ITEM1, GROUND_ITEM2, GROUND_ITEM3, GROUND_ITEM4, GROUND_ITEM5, GROUND_ITEM6
    };

    // ---------------- Player ----------------
    public static final int SELECT_PLAYER = 15;
    public static final int PLAYER1 = 44;
    public static final int PLAYER2 = 45;
    public static final int PLAYER3 = 46;
    public static final int PLAYER4 = 47;
    public static final int PLAYER5 = 48;
    public static final int PLAYER6 = 49;
    public static final int PLAYER7 = 50;
    public static final int PLAYER8 = 51;
    public static final int PLAYER9 = 52;
    public static final int PLAYER10 = 53;
    public static final int[] PLAYER_OPTIONS = {
            0, PLAYER1, PLAYER2, PLAYER3, PLAYER4, PLAYER5,
            PLAYER6, PLAYER7, PLAYER8, PLAYER9, PLAYER10
    };

    // ---------------- Component ----------------
    public static final int SELECT_COMPONENT = 25;
    public static final int COMPONENT = 57;
    public static final int SELECT_COMPONENT_ITEM = 58;
    public static final int COMP_ON_PLAYER = 16;
    public static final int COMPONENT_KEY = 5000;
    public static final int COMPONENT_DRAG = 5001;
    public static final int RADIO_GROUP_SELECT = 5002;
    public static final int COMPONENT_SPECIAL = 1007;

    // ---------------- Misc ----------------
    public static final int SELECT_TILE = 59;
    public static final int FACE_TILE = 1006;
    public static final int DIALOGUE = 30;
}
