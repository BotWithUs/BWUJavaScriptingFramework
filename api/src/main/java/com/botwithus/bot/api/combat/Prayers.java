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

    private static final GamevalRef POINTS =
            new GamevalRef(GamevalType.VARBIT, "PRAYER_POINTS", 16736);
    private static final GamevalRef MODE = new GamevalRef(GamevalType.VARBIT, "PRAYER_MODE", 16789);
    private static final GamevalRef QUICK_PRAYERS_ON =
            new GamevalRef(GamevalType.VARBIT, "TOPSTAT_PRAYER_BUTTON", 5941);
    private static final GamevalRef PRAYER_WINDOW =
            new GamevalRef(GamevalType.INTERFACE, "TOPLEVEL_V2_PRAYER", 1458);
    /** The quick-prayer orb on the combat bar. */
    private static final GamevalRef QUICK_PRAYER_ORB = new GamevalRef(GamevalType.COMPONENT,
            "TOPLEVEL_V2_COMBAT_BAR__PRAYER_BUTTON_LAYER", Interfaces.componentHash(1430, 17));
    private static final int WHOLE_INTERFACE = 0;
    private static final int NO_SUB_INDEX = -1;

    private final GameAPI api;

    public Prayers(GameAPI api) {
        this.api = api;
    }

    /** Current prayer points as the orb shows them (990 at 99 Prayer, full). */
    public int points() {
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

    private Optional<ComponentTreeNode> findButton(PrayerInfo info) {
        int window = PRAYER_WINDOW.resolve(api.gamevals());
        if (!api.isInterfaceOpen(window)) {
            return Optional.empty();
        }
        return api.getInterfaceTree(window, WHOLE_INTERFACE).stream()
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
