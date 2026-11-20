package com.botwithus.bot.api.combat;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalIndex;
import com.botwithus.bot.api.gameval.GamevalRef;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.ComponentTreeNode;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.StructType;
import com.botwithus.bot.api.model.VarbitValue;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.Skill;
import com.botwithus.bot.api.util.Interfaces;
import com.botwithus.bot.api.util.SkillType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Prayer points, the active book, which prayers are on, and turning them on and off.
 *
 * <p>Reads are varbits addressed by gameval name. Prayer definitions come from the cache
 * structs ({@link #info(Prayer)}). Toggling clicks the prayer's button in the prayer window
 * ({@code TOPLEVEL_V2_PRAYER}), found by the sprite its struct names, so the window has to be
 * open: {@link #toggle(Prayer)} returns {@code false} when it is not.</p>
 */
public final class Prayers {

    /** The varbit value of a prayer that is on. */
    private static final int ON = 1;
    private static final int OP_TOGGLE = 1;
    private static final int POINTS_PER_LEVEL = 10;
    /** {@code PRAYER_POINTS} stores tenths of a point. */
    private static final int TENTHS = 10;

    private static final GamevalRef POINTS =
            new GamevalRef(GamevalType.VARBIT, "PRAYER_POINTS", 16736);
    private static final GamevalRef MODE = new GamevalRef(GamevalType.VARBIT, "PRAYER_MODE", 16789);
    private static final GamevalRef QUICK_PRAYERS_ON =
            new GamevalRef(GamevalType.VARBIT, "TOPSTAT_PRAYER_BUTTON", 5941);
    private static final GamevalRef PRAYER_WINDOW =
            new GamevalRef(GamevalType.INTERFACE, "TOPLEVEL_V2_PRAYER", 1458);
    /**
     * The quick-prayer orb on the combat bar. Op 1 is "Turn quick prayers on/off", op 2
     * "Select quick prayers" (read live with {@code get_component_ops}).
     */
    private static final GamevalRef QUICK_PRAYER_ORB = new GamevalRef(GamevalType.COMPONENT,
            "TOPLEVEL_V2_COMBAT_BAR__PRAYER_GLOBE_LAYER", Interfaces.componentHash(1430, 18));
    private static final int OP_SELECT_QUICK_PRAYERS = 2;
    /** The ribbon's button grid; each ribbon slot is a sub of it. */
    private static final GamevalRef RIBBON = new GamevalRef(GamevalType.COMPONENT,
            "TOPLEVEL_V2_RIBBON__WINDOW_BUILD", Interfaces.componentHash(1431, 0));
    /** Cache enum: ribbon slot to the toplevel window it opens. */
    private static final GamevalRef RIBBON_SLOT_TO_WINDOW =
            new GamevalRef(GamevalType.ENUM, "RIBBON_WINDOWS", 13319);
    /** Toplevel window number of the prayer window (its row in {@code TOPLEVEL_V2_WINDOW_ID_TO_DATA} is titled Prayers). */
    private static final int PRAYER_WINDOW_NUMBER = 4;
    /** Ribbon slot of the prayer window on the current cache; used when the enum is unreadable. */
    private static final int PRAYER_RIBBON_SLOT = 11;
    /**
     * The prayer button grid. Every prayer tier is its own sub, showing its
     * {@code PRAYER_GRAPHIC_ON} sprite when unlocked and {@code PRAYER_GRAPHIC_OFF} when not.
     */
    private static final GamevalRef PRAYER_BUTTONS = new GamevalRef(GamevalType.COMPONENT,
            "TOPLEVEL_V2_PRAYER__PRAYER_BUTTONS", Interfaces.componentHash(1458, 40));
    private static final int NO_SUB_INDEX = -1;

    private final GameAPI api;

    public Prayers(GameAPI api) {
        this.api = api;
    }

    /**
     * Current prayer points as the orb shows them (990 at 99 Prayer, full). The varbit holds
     * tenths: live, a character at Prayer 2 read {@code PRAYER_POINTS} 200 with the orb
     * showing "20/20". {@code -1} when unreadable.
     */
    public int points() {
        int raw = rawPoints();
        return raw < 0 ? -1 : raw / TENTHS;
    }

    /** {@code PRAYER_POINTS} as stored: tenths of a point. */
    public int rawPoints() {
        return api.getVarbit(POINTS.resolve(api.gamevals()));
    }

    /** Full prayer points: base Prayer level times ten; {@code -1} when it cannot be read. */
    public int maxPoints() {
        GameSnapshot snapshot = api.snapshot();
        if (snapshot == null || snapshot.self() == null) {
            return -1;
        }
        return snapshot.self().skills().stream()
                .filter(skill -> skill.typeId() == SkillType.PRAYER.id())
                .mapToInt(skill -> skill.actualLevel() * POINTS_PER_LEVEL)
                .findFirst().orElse(-1);
    }

    /** The active book. */
    public PrayerBook activeBook() {
        return PrayerBook.fromModeValue(api.getVarbit(MODE.resolve(api.gamevals())));
    }

    /** True when {@code prayer} is on. */
    public boolean isActive(Prayer prayer) {
        return api.getVarbit(prayer.activeVarbit().resolve(api.gamevals())) == ON;
    }

    /** True when any of {@code prayers} is on. One varbit batch, whatever the count. */
    public boolean isAnyActive(Prayer... prayers) {
        return !active(Arrays.asList(prayers)).isEmpty();
    }

    /** True when every one of {@code prayers} is on. */
    public boolean isAllActive(Prayer... prayers) {
        return active(Arrays.asList(prayers)).size() == prayers.length;
    }

    /** Every prayer of the active book that is on. */
    public List<Prayer> activePrayers() {
        return active(activeBook().prayers());
    }

    /**
     * {@code prayer}'s definition. A tiered prayer reports the tier the base Prayer level
     * selects (the lowest tier when the level cannot be read). Empty with no cache.
     */
    public Optional<PrayerInfo> info(Prayer prayer) {
        return struct(prayer.struct().resolve(api.gamevals()))
                .map(struct -> tierFor(struct).orElse(struct))
                .map(struct -> PrayerInfo.fromStruct(prayer, struct));
    }

    /**
     * Clicks {@code prayer}'s button in the open prayer window, which turns it on if it was
     * off and off if it was on. Returns whether the click was queued; read
     * {@link #isActive(Prayer)} a tick later to see the result. {@code false} when the window
     * is not open or the button cannot be found.
     */
    public boolean toggle(Prayer prayer) {
        Optional<PrayerInfo> info = info(prayer);
        if (info.isEmpty()) {
            return false;
        }
        Optional<ComponentTreeNode> button = findButton(info.get());
        button.ifPresent(node -> api.queueAction(new GameAction(ActionTypes.COMPONENT,
                OP_TOGGLE, node.component().subId(),
                (node.component().ifaceId() << GamevalIndex.INTERFACE_ID_SHIFT)
                        | node.component().compId())));
        return button.isPresent();
    }

    /** True when the prayer window is open. */
    public boolean isWindowOpen() {
        return api.isInterfaceOpen(PRAYER_WINDOW.resolve(api.gamevals()));
    }

    /**
     * Opens the prayer window from the ribbon when it is closed. The ribbon button toggles,
     * so this does nothing when the window is already open. Returns whether it is open or a
     * click to open it was queued.
     */
    public boolean openWindow() {
        if (isWindowOpen()) {
            return true;
        }
        api.queueAction(new GameAction(ActionTypes.COMPONENT, OP_TOGGLE, prayerRibbonSlot(),
                RIBBON.resolve(api.gamevals())));
        return true;
    }

    /** True when the quick-prayer orb is on. */
    public boolean isQuickPrayersActive() {
        return api.getVarbit(QUICK_PRAYERS_ON.resolve(api.gamevals())) == ON;
    }

    /** Clicks the quick-prayer orb. Returns whether the click was queued. */
    public boolean toggleQuickPrayers() {
        api.queueAction(new GameAction(ActionTypes.COMPONENT, OP_TOGGLE, NO_SUB_INDEX,
                QUICK_PRAYER_ORB.resolve(api.gamevals())));
        return true;
    }

    /**
     * Opens quick-prayer selection ("Select quick prayers" on the orb): while it is open, a
     * {@link #toggle(Prayer)} adds the prayer to or removes it from the quick set instead of
     * turning it on. Click the orb's confirm (or this again) to leave. Returns whether the
     * click was queued.
     */
    public boolean openQuickPrayerSelection() {
        api.queueAction(new GameAction(ActionTypes.COMPONENT, OP_SELECT_QUICK_PRAYERS,
                NO_SUB_INDEX, QUICK_PRAYER_ORB.resolve(api.gamevals())));
        return true;
    }

    private List<Prayer> active(List<Prayer> prayers) {
        if (prayers.isEmpty()) {
            return List.of();
        }
        GamevalIndex index = api.gamevals();
        List<Integer> ids = prayers.stream().map(p -> p.activeVarbit().resolve(index)).toList();
        List<VarbitValue> values = api.queryVarbits(ids);
        List<Prayer> on = new ArrayList<>();
        for (int i = 0; i < prayers.size() && i < values.size(); i++) {
            if (values.get(i).value() == ON) {
                on.add(prayers.get(i));
            }
        }
        return List.copyOf(on);
    }

    private int prayerRibbonSlot() {
        try {
            var slots = api.getEnumType(RIBBON_SLOT_TO_WINDOW.resolve(api.gamevals()));
            if (slots != null) {
                for (var entry : slots.intEntries().entrySet()) {
                    if (entry.getValue() == PRAYER_WINDOW_NUMBER) {
                        return entry.getKey();
                    }
                }
            }
        } catch (IllegalStateException noCache) {
            // fall through to the literal
        }
        return PRAYER_RIBBON_SLOT;
    }

    private Optional<ComponentTreeNode> findButton(PrayerInfo info) {
        int window = PRAYER_WINDOW.resolve(api.gamevals());
        if (!api.isInterfaceOpen(window)) {
            return Optional.empty();
        }
        int buttons = PRAYER_BUTTONS.resolve(api.gamevals());
        return api.getInterfaceTree(Interfaces.interfaceIdFromHash(buttons),
                        Interfaces.componentIdFromHash(buttons)).stream()
                .filter(node -> node.component().spriteId() == info.graphicOn()
                        || node.component().spriteId() == info.graphicOff())
                .filter(node -> !node.component().triggers().isEmpty())
                .findFirst();
    }

    private Optional<StructType> tierFor(StructType tiered) {
        int level = prayerLevel();
        Optional<StructType> chosen = Optional.empty();
        for (int tier = 0; tier < PrayerInfo.TIERS; tier++) {
            int levelParam = PrayerInfo.PARAM_TIER1_LEVEL + 2 * tier;
            OptionalInt needed = tiered.intParam(levelParam);
            OptionalInt structId = tiered.intParam(levelParam + 1);
            if (needed.isEmpty() || structId.isEmpty()) {
                break;
            }
            if (chosen.isEmpty() || level >= needed.getAsInt()) {
                chosen = struct(structId.getAsInt());
            }
        }
        return chosen;
    }

    private int prayerLevel() {
        GameSnapshot snapshot = api.snapshot();
        if (snapshot == null || snapshot.self() == null) {
            return 0;
        }
        return snapshot.self().skills().stream()
                .filter(skill -> skill.typeId() == SkillType.PRAYER.id())
                .mapToInt(Skill::actualLevel).findFirst().orElse(0);
    }

    private Optional<StructType> struct(int id) {
        try {
            return Optional.ofNullable(api.getStructType(id));
        } catch (IllegalStateException noCache) {
            return Optional.empty();
        }
    }
}
