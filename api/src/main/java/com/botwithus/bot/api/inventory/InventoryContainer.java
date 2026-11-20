package com.botwithus.bot.api.inventory;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.model.ItemType;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.Inventory;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.util.Interfaces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Snapshot-backed wrapper for one game inventory (backpack, bank, equipment, ...).
 *
 * <p>Reads ride on {@link com.botwithus.bot.api.snapshot.GameSnapshot#inventories()}
 * — no RPC for read paths. Item-name lookup goes through
 * {@link GameAPI#getItemType ItemType definitions} cached by id at the
 * subclass level (one cache per facade instance).</p>
 *
 * <p>Slot interactions build a {@link GameAction} with action id
 * {@link ActionTypes#COMPONENT}, the right-click option index in
 * {@code param1}, the slot index (sub-component id) in {@code param2}, and
 * the packed {@code (iface<<16)|comp} of the slot grid in {@code param3}.
 * The packing matches what {@code ApplyDeferredUpdate} uses on the producer
 * side.</p>
 *
 * <p>Subclasses ({@link Backpack}, {@link Bank}, {@link Equipment}) bind
 * the inventory id and the slot-grid component coordinates; this class
 * holds the queries.</p>
 */
public class InventoryContainer {

    private static final Logger log = LoggerFactory.getLogger(InventoryContainer.class);

    protected final GameAPI api;
    protected final int invId;
    protected final int interfaceId;
    protected final int componentId;
    /**
     * The option name routed to the cache-index lookup instead of the live ops. "Use" is
     * the client's item-selection verb and never one of a component's ops 1..10, and the
     * action that selects an inventory item for use-on has not been established, so this
     * keeps the behaviour {@link #interactFirst(int, String)} had for it before the live
     * lookup, unchanged.
     */
    public static final String USE = "Use";

    /**
     * Shared definition cache backing the {@link #itemTypeLookup} closure.
     * Held on the abstraction so subclasses do not introduce hidden state
     * — every container kind (backpack, bank, equipment, future inventories)
     * gets the same cache contract for free.
     */
    private final ConcurrentHashMap<Integer, ItemType> defCache;
    private final IntFunction<ItemType> itemTypeLookup;

    /**
     * @param api         game API for action queueing + snapshot reads
     * @param invId       inventory id (matches {@link com.botwithus.bot.api.constants.InventoryIds})
     * @param interfaceId iface id of the slot-grid component used for click interactions
     * @param componentId component id of the slot-grid within {@code interfaceId}
     */
    protected InventoryContainer(GameAPI api,
                                 int invId,
                                 int interfaceId,
                                 int componentId) {
        this.api = api;
        this.invId = invId;
        this.interfaceId = interfaceId;
        this.componentId = componentId;
        this.defCache = new ConcurrentHashMap<>();
        this.itemTypeLookup = cachedItemTypeLookup(api, defCache);
    }

    /**
     * Read-only construction for ad-hoc inventories (shops, generic containers)
     * where the script only needs item-list reads. Interface/component IDs are
     * unset, so {@code interact} calls will fail — use {@link Backpack},
     * {@link Bank}, or {@link Equipment} for any inventory with click targets.
     */
    public InventoryContainer(GameAPI api, int invId) {
        this(api, invId, -1, -1);
    }

    public int invId()        { return invId; }
    public int interfaceId()  { return interfaceId; }
    public int componentId()  { return componentId; }

    // ---------------------------------------------------------------- Definition cache

    /** Drop the ItemType cache. Useful between game updates or in tests. */
    public final void clearDefinitionCache() {
        defCache.clear();
    }

    /** Diagnostic — number of cached ItemType definitions. */
    public final int definitionCacheSize() {
        return defCache.size();
    }

    // ---------------------------------------------------------------- Slot reads

    /** Resolves the inventory from the current snapshot, or {@code null} if not published. */
    private Inventory resolve() {
        GameSnapshot snap = api.snapshot();
        if (snap == null) {
            return null;
        }
        Optional<Inventory> opt = snap.inventories().byInvId(invId);
        return opt.orElse(null);
    }

    /** All slots, including empties. Empty list if the inventory isn't published yet. */
    public List<InventoryItem> getAllSlots() {
        Inventory inv = resolve();
        return inv == null ? List.of() : inv.items();
    }

    /** Non-empty slots only. */
    public List<InventoryItem> getItems() {
        List<InventoryItem> all = getAllSlots();
        if (all.isEmpty()) {
            return all;
        }
        List<InventoryItem> filled = new ArrayList<>(all.size());
        for (InventoryItem it : all) {
            if (!it.isEmpty()) {
                filled.add(it);
            }
        }
        return filled;
    }

    /** One slot by 0-based index, or {@code null} when out of range. */
    public InventoryItem getSlot(int slot) {
        List<InventoryItem> all = getAllSlots();
        return (slot < 0 || slot >= all.size()) ? null : all.get(slot);
    }

    /** Slot count from the snapshot header (0 when not published). */
    public int slotCount() {
        Inventory inv = resolve();
        return inv == null ? 0 : inv.slotCount();
    }

    public int occupiedSlots() {
        int n = 0;
        for (InventoryItem it : getAllSlots()) {
            if (!it.isEmpty()) {
                ++n;
            }
        }
        return n;
    }

    public int freeSlots() {
        return Math.max(0, slotCount() - occupiedSlots());
    }

    public boolean isEmpty()    { return occupiedSlots() == 0; }
    public boolean isNotEmpty() { return !isEmpty(); }
    public boolean isFull()     { return slotCount() > 0 && freeSlots() == 0; }
    public boolean isNotFull()  { return !isFull(); }

    // ---------------------------------------------------------------- Obj vars

    /**
     * Per-item obj vars for one slot — the per-instance variables the item in
     * that slot carries inside its {@code ObjVarDomain} (augmentation XP,
     * charges, etc.). Returns {@code varId -> value}, empty when the slot is
     * empty or carries no obj vars. On-demand RPC read (not snapshot-backed).
     */
    public Map<Integer, Integer> getSlotVars(int slot) {
        return api.getObjVars(invId, slot);
    }

    /**
     * Per-item obj vars for every filled slot in this container. Returns
     * {@code slot -> (varId -> value)}, empty when nothing here carries obj
     * vars. On-demand RPC read (not snapshot-backed).
     */
    public Map<Integer, Map<Integer, Integer>> getAllSlotVars() {
        return api.getObjVars(invId);
    }

    // ---------------------------------------------------------------- Containment

    public boolean contains(int itemId) {
        for (InventoryItem it : getAllSlots()) {
            if (it.itemId() == itemId) {
                return true;
            }
        }
        return false;
    }

    /** True when the total quantity of {@code itemId} is at least {@code amount}. */
    public boolean contains(int itemId, int amount) {
        return count(itemId) >= amount;
    }

    public boolean containsAny(int... itemIds) {
        for (int id : itemIds) {
            if (contains(id)) {
                return true;
            }
        }
        return false;
    }

    public boolean containsAll(int... itemIds) {
        for (int id : itemIds) {
            if (!contains(id)) {
                return false;
            }
        }
        return true;
    }

    /** Containment by name (case-insensitive substring match against ItemType.name). */
    public boolean contains(String name) {
        return getFirst(name) != null;
    }

    public int count(int itemId) {
        int n = 0;
        for (InventoryItem it : getAllSlots()) {
            if (it.itemId() == itemId) {
                n += it.quantity();
            }
        }
        return n;
    }

    /** Total quantity of items whose name matches (case-insensitive contains). */
    public int count(String name) {
        int n = 0;
        for (InventoryItem it : getAllSlots()) {
            if (matchesName(it, name)) {
                n += it.quantity();
            }
        }
        return n;
    }

    public InventoryItem getFirst(int itemId) {
        for (InventoryItem it : getAllSlots()) {
            if (it.itemId() == itemId) {
                return it;
            }
        }
        return null;
    }

    public Optional<InventoryItem> findFirst(int itemId) {
        return Optional.ofNullable(getFirst(itemId));
    }

    public InventoryItem getFirst(String name) {
        for (InventoryItem it : getAllSlots()) {
            if (matchesName(it, name)) {
                return it;
            }
        }
        return null;
    }

    public Optional<InventoryItem> findFirst(String name) {
        return Optional.ofNullable(getFirst(name));
    }

    public List<InventoryItem> getAll(String name) {
        List<InventoryItem> out = new ArrayList<>();
        for (InventoryItem it : getAllSlots()) {
            if (matchesName(it, name)) {
                out.add(it);
            }
        }
        return out;
    }

    private boolean matchesName(InventoryItem it, String needle) {
        if (it.isEmpty()) {
            return false;
        }
        ItemType t = lookupType(it.itemId());
        if (t == null || t.name() == null) {
            return false;
        }
        return t.name().toLowerCase().contains(needle.toLowerCase());
    }

    // ------------------------------------------------- Exact names, patterns, categories

    /*
     * Unlike the substring String methods above, everything in this section that takes a name
     * matches the WHOLE display name, ignoring case: "Logs" finds Logs and not Oak logs. A
     * Pattern must match the whole name too (Matcher.matches, not find). The difference is
     * deliberate: these methods decide what to click or deposit, where a substring match
     * picks the wrong item.
     */

    /** True when some item's whole display name matches {@code namePattern}. */
    public boolean contains(Pattern namePattern) {
        return getFirst(namePattern) != null;
    }

    /** Total quantity of items whose whole display name matches {@code namePattern}. */
    public int count(Pattern namePattern) {
        return countMatching(byPattern(namePattern));
    }

    /** First slot whose item's whole display name matches {@code namePattern}, or {@code null}. */
    public InventoryItem getFirst(Pattern namePattern) {
        return firstMatching(byPattern(namePattern));
    }

    /** Every slot whose item's whole display name matches {@code namePattern}. */
    public List<InventoryItem> getAll(Pattern namePattern) {
        return allMatching(byPattern(namePattern));
    }

    /** First slot whose item is named exactly {@code name}, ignoring case, or {@code null}. */
    public InventoryItem getFirstExact(String name) {
        return firstMatching(byExactNames(name));
    }

    /** True when every one of {@code names} is present, each matched as a whole name. */
    public boolean containsAllOf(String... names) {
        for (String name : names) {
            if (getFirstExact(name) == null) {
                return false;
            }
        }
        return true;
    }

    /** True when any of {@code names} is present, each matched as a whole name. */
    public boolean containsAnyOf(String... names) {
        return firstMatching(byExactNames(names)) != null;
    }

    /** True when some item other than {@code itemIds} is present. */
    public boolean containsAnyExcept(int... itemIds) {
        return firstMatching(byIds(itemIds).negate()) != null;
    }

    /** True when some item not named any of {@code names} (whole names) is present. */
    public boolean containsAnyExcept(String... names) {
        return firstMatching(byExactNames(names).negate()) != null;
    }

    /** True when some item's cache category ({@link ItemType#category()}) is {@code category}. */
    public boolean containsCategory(int category) {
        return firstMatching(byCategory(category)) != null;
    }

    /** Every slot whose item's cache category is {@code category}. */
    public List<InventoryItem> getAllInCategory(int category) {
        return allMatching(byCategory(category));
    }

    /**
     * Every slot whose item lists {@code option} (ignoring case) in its cache inventory
     * options, {@link ItemType#inventoryOptions()}. That list is what the definition stores,
     * so the client's default {@code "Drop"} is not in it; for what a live slot offers, read
     * {@link GameAPI#getComponentOps} instead.
     */
    public List<InventoryItem> getItemsWithOption(String option) {
        return allMatching(it -> {
            ItemType t = lookupType(it.itemId());
            return t != null && t.inventoryOptions().stream().anyMatch(option::equalsIgnoreCase);
        });
    }

    /**
     * {@link #interactFirst(int, String)} on the first item named exactly {@code name}.
     *
     * @return {@code false} when no such item is present or the option is not offered
     */
    public boolean interactFirst(String name, String option) {
        InventoryItem it = getFirstExact(name);
        return it != null && interactFirst(it.itemId(), option);
    }

    /** {@link #interactFirst(int, String)} on the first item whose name matches {@code namePattern}. */
    public boolean interactFirst(Pattern namePattern, String option) {
        InventoryItem it = getFirst(namePattern);
        return it != null && interactFirst(it.itemId(), option);
    }

    /** Alias for {@link #interactFirst(String, String)}: the item named exactly {@code name}. */
    public boolean interact(String name, String option) {
        return interactFirst(name, option);
    }

    /** Alias for {@link #interactFirst(Pattern, String)}. */
    public boolean interact(Pattern namePattern, String option) {
        return interactFirst(namePattern, option);
    }

    /** Matches a filled slot whose item's whole display name matches {@code pattern}. */
    protected final Predicate<InventoryItem> byPattern(Pattern pattern) {
        return it -> {
            String name = displayName(it);
            return name != null && pattern.matcher(name).matches();
        };
    }

    /** Matches a filled slot whose item's whole display name matches any of {@code patterns}. */
    protected final Predicate<InventoryItem> byPatterns(Pattern... patterns) {
        Predicate<InventoryItem> any = it -> false;
        for (Pattern pattern : patterns) {
            any = any.or(byPattern(pattern));
        }
        return any;
    }

    /** Matches a filled slot whose item is named exactly any of {@code names}, ignoring case. */
    protected final Predicate<InventoryItem> byExactNames(String... names) {
        List<String> wanted = List.of(names);
        return it -> {
            String name = displayName(it);
            return name != null && wanted.stream().anyMatch(name::equalsIgnoreCase);
        };
    }

    /** Matches a slot holding any of {@code itemIds}. */
    protected static Predicate<InventoryItem> byIds(int... itemIds) {
        int[] ids = itemIds.clone();
        return it -> {
            for (int id : ids) {
                if (it.itemId() == id) {
                    return true;
                }
            }
            return false;
        };
    }

    private Predicate<InventoryItem> byCategory(int category) {
        return it -> {
            ItemType t = lookupType(it.itemId());
            return t != null && t.category() == category;
        };
    }

    /** First filled slot {@code matcher} accepts, or {@code null}. Empty slots never match. */
    protected final InventoryItem firstMatching(Predicate<InventoryItem> matcher) {
        for (InventoryItem it : getAllSlots()) {
            if (!it.isEmpty() && matcher.test(it)) {
                return it;
            }
        }
        return null;
    }

    /** Every filled slot {@code matcher} accepts. Empty slots never match. */
    protected final List<InventoryItem> allMatching(Predicate<InventoryItem> matcher) {
        List<InventoryItem> out = new ArrayList<>();
        for (InventoryItem it : getAllSlots()) {
            if (!it.isEmpty() && matcher.test(it)) {
                out.add(it);
            }
        }
        return out;
    }

    private int countMatching(Predicate<InventoryItem> matcher) {
        int n = 0;
        for (InventoryItem it : allMatching(matcher)) {
            n += it.quantity();
        }
        return n;
    }

    /** The item's display name, or {@code null} for an empty slot or an unresolved definition. */
    private String displayName(InventoryItem it) {
        if (it.isEmpty()) {
            return null;
        }
        ItemType t = lookupType(it.itemId());
        return t == null ? null : t.name();
    }

    // ------------------------------------------------------------ Gameval names

    /*
     * The String-taking methods above match the localised DISPLAY name by
     * case-insensitive substring. The ones below match a gameval symbolic name
     * exactly. The two are deliberately NOT overloads of each other — same
     * signature, opposite semantics — so do not "unify" them later.
     */

    /** Containment by gameval name, e.g. {@code "YEW_LOGS"}. */
    public boolean containsGameval(String gameval) {
        OptionalInt id = gamevalItemId(gameval);
        return id.isPresent() && contains(id.getAsInt());
    }

    /** Total quantity of the item with the given gameval name. */
    public int countGameval(String gameval) {
        OptionalInt id = gamevalItemId(gameval);
        return id.isPresent() ? count(id.getAsInt()) : 0;
    }

    /** First slot holding the item with the given gameval name, or {@code null}. */
    public InventoryItem getFirstGameval(String gameval) {
        OptionalInt id = gamevalItemId(gameval);
        return id.isPresent() ? getFirst(id.getAsInt()) : null;
    }

    /** First slot holding the item with the given gameval name, as an Optional. */
    public Optional<InventoryItem> findFirstGameval(String gameval) {
        return Optional.ofNullable(getFirstGameval(gameval));
    }

    /**
     * Click the first slot holding the item with the given gameval name, using
     * the given 1-based option index. {@code false} when the name doesn't
     * resolve or the item isn't present.
     */
    public boolean interactFirstGameval(String gameval, int optionIndex) {
        OptionalInt id = gamevalItemId(gameval);
        return id.isPresent() && interactFirst(id.getAsInt(), optionIndex);
    }

    /**
     * Click the first slot holding the item with the given gameval name, using
     * the named option. {@code false} when the name doesn't resolve, the item
     * isn't present, or the option isn't offered.
     */
    public boolean interactFirstGameval(String gameval, String option) {
        OptionalInt id = gamevalItemId(gameval);
        return id.isPresent() && interactFirst(id.getAsInt(), option);
    }

    /** Item id for a gameval name, or empty when it doesn't resolve. */
    private OptionalInt gamevalItemId(String gameval) {
        return api.gamevals().id(GamevalType.ITEM, gameval);
    }

    /** Pull ItemType through the subclass-provided cache. {@code null} on failure. */
    protected ItemType lookupType(int itemId) {
        return itemTypeLookup.apply(itemId);
    }

    // ---------------------------------------------------------------- Interactions

    /**
     * Click the given slot with the given 1-based right-click option index.
     * Builds a {@link GameAction} with action id {@link ActionTypes#COMPONENT}
     * (57), option in {@code param1}, the slot index in {@code param2}, and the
     * packed {@code (iface<<16)|comp} of the slot grid in {@code param3}.
     *
     * @return {@code true} when a queue_action was sent (slot is in range);
     *         {@code false} when the slot index is out of range
     */
    public boolean interact(int slot, int optionIndex) {
        if (slot < 0 || slot >= slotCount()) {
            return false;
        }
        api.queueAction(new GameAction(
                ActionTypes.COMPONENT,
                optionIndex,
                slot,
                Interfaces.componentHash(interfaceId, componentId)));
        return true;
    }

    /**
     * Click the first slot containing {@code itemId} with the given 1-based
     * option index (no name lookup). Returns {@code false} if the item isn't
     * in the inventory.
     */
    public boolean interactFirst(int itemId, int optionIndex) {
        InventoryItem it = getFirst(itemId);
        if (it == null) {
            return false;
        }
        return interact(it.slot(), optionIndex);
    }

    /**
     * Click the first slot containing {@code itemId} with the named option.
     *
     * <p>The name is matched, case-insensitively, against the slot's <em>live</em> ops
     * ({@link GameAPI#getComponentOps}), and the click sends the op the client has under
     * that name. It is not looked up in {@link ItemType#inventoryOptions()}: an option's
     * position in the cache list is not the op the client sends, and the client's default
     * Drop is not in that list at all, so that lookup clicked a different option or
     * none. One pipe round-trip for the lookup, one for the click.</p>
     *
     * <p><b>Preconditions.</b> The container's interface must be open (for the backpack,
     * its window), because a closed one does not resolve and its slot has no ops to read.
     * The agent must have {@code get_component_ops}. Either one missing makes this return
     * {@code false}; the host logs a WARN naming which, once per connection.</p>
     *
     * <p><b>{@code "Use"} is the exception.</b> It is the client's target verb, not one of
     * the ops 1..10, so the live ops never list it. It is resolved as it was before the
     * live lookup existed: the name's 1-based position in the item's cache options, sent as
     * the op. That is not known to select the item for use-on; see {@link #USE}.</p>
     *
     * @return {@code false} when the item isn't present, or no listed op of its slot has
     *         that name (including a closed interface or an agent too old to report ops);
     *         nothing is sent then
     */
    public boolean interactFirst(int itemId, String option) {
        InventoryItem it = getFirst(itemId);
        if (it == null) {
            return false;
        }
        if (USE.equalsIgnoreCase(option)) {
            return interactByCacheIndex(it, option);
        }
        OptionalInt op = api.getComponentOps(interfaceId, componentId, it.slot()).opNamed(option);
        if (op.isEmpty()) {
            log.debug("inventory {} slot {}: no listed op named '{}' on item {}",
                    invId, it.slot(), option, itemId);
            return false;
        }
        return interact(it.slot(), op.getAsInt());
    }

    /**
     * The pre-live-ops lookup, kept for {@link #USE} only: the option's 1-based position in
     * the item's cache options, sent as the op. False when the item has no definition or
     * the name is not in its cache options.
     */
    private boolean interactByCacheIndex(InventoryItem it, String option) {
        ItemType t = lookupType(it.itemId());
        if (t == null) {
            return false;
        }
        int idx = findOptionIndex(t.inventoryOptions(), option);
        if (idx < 1) {
            return false;
        }
        return interact(it.slot(), idx);
    }

    private static int findOptionIndex(List<String> options, String wanted) {
        if (options == null || wanted == null) {
            return -1;
        }
        for (int i = 0; i < options.size(); ++i) {
            if (wanted.equalsIgnoreCase(options.get(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    /** Alias for {@link #interactFirst(int, String)}. */
    public boolean interact(int itemId, String option) {
        return interactFirst(itemId, option);
    }

    /**
     * Sentinel marking "lookup returned null" — ConcurrentHashMap rejects
     * null values, so we cache failures as this constant and translate back
     * to {@code null} at the read site.
     */
    private static final ItemType NULL_DEF = new ItemType(
            -1, "", false, false, 0, 0, 0, -1, -1, false, List.of(), List.of(), Map.of());

    /**
     * Build an IntFunction&lt;ItemType&gt; backed by {@code cache} that calls
     * {@link GameAPI#getItemType} on first miss. Failed lookups cache as
     * {@link #NULL_DEF} so we don't retry forever.
     */
    private static IntFunction<ItemType> cachedItemTypeLookup(GameAPI api,
                                                              ConcurrentHashMap<Integer, ItemType> cache) {
        return id -> {
            ItemType c = cache.get(id);
            if (c != null) {
                return c == NULL_DEF ? null : c;
            }
            ItemType fetched;
            try {
                fetched = api.getItemType(id);
            } catch (RuntimeException e) {
                log.debug("getItemType({}) failed; caching null sentinel", id, e);
                fetched = null;
            }
            cache.put(id, fetched != null ? fetched : NULL_DEF);
            return fetched;
        };
    }
}
