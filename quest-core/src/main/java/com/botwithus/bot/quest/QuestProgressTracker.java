package com.botwithus.bot.quest;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.event.VarChangeEvent;
import com.botwithus.bot.api.event.VarbitChangeEvent;
import com.botwithus.bot.api.model.VarbitValue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Per-quest cache of tracker-var values. Updates incrementally from
 * {@link VarChangeEvent} / {@link VarbitChangeEvent} on the bus, and re-seeds
 * the entire tuple from a batch RPC every {@link #HEARTBEAT_MS} ms — the
 * event stream is a best-effort signal (single dropped delta strands the
 * tracker on a stale value), so we always have a self-healing path.
 *
 * <p>Every tracker variable is read in its own id space, as its
 * {@link TrackerVar.Kind kind} says: varps through {@code getVarps}, varbits through
 * {@code queryVarbits}, and a varp change event only ever touches a tracked varp (and
 * likewise for varbits). The tracker used to read each id as both kinds and keep the
 * varp answer when it was non-negative. That worked only while an unset varp read -1.
 * An agent that reports an unset varp as 0 would have made the varp answer win for a
 * quest tracked in varbits, and the quest would read "not started" forever.</p>
 *
 * <p>A negative batch answer means the value is not known this time (the read failed,
 * or an older agent reported an unset varp as -1). The last known value is kept rather
 * than overwritten, and a variable never read yet stays at 0, which for a quest
 * variable means "not started". Quest progress variables are never legitimately
 * negative.</p>
 *
 * <p>Threading: event handlers run on the bus thread; {@link #current()} can
 * be called from any thread (the router task runs on the script's virtual
 * thread). The cache is a {@link ConcurrentHashMap} and the snapshot
 * exposed through {@link QuestState} is an immutable copy, so reads never
 * see a partial update.</p>
 *
 * <p>Construction subscribes to both event types and triggers the initial
 * seed; {@link #close()} unsubscribes. The script base class owns lifetime —
 * scripts should not new-up trackers directly.</p>
 */
public final class QuestProgressTracker implements AutoCloseable {

    /** Heartbeat budget — after this long without a seed, {@link #current()} re-queries. */
    public static final long HEARTBEAT_MS = 5_000L;

    private final GameAPI api;
    private final EventBus eventBus;
    private final int[] varpIds;
    private final int[] varbitIds;
    private final Consumer<VarChangeEvent> varpListener;
    private final Consumer<VarbitChangeEvent> varbitListener;
    private final Map<Integer, Integer> cache = new ConcurrentHashMap<>();
    private volatile long lastHeartbeatMs;

    public QuestProgressTracker(QuestId quest, ScriptContext ctx) {
        this(quest, ctx.getGameAPI(), ctx.getEventBus());
    }

    /** Constructor for tests that wire {@link GameAPI} and {@link EventBus} directly. */
    public QuestProgressTracker(QuestId quest, GameAPI api, EventBus eventBus) {
        Objects.requireNonNull(quest, "quest");
        this.api = Objects.requireNonNull(api, "api");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.varpIds = quest.trackerVarps();
        this.varbitIds = quest.trackerVarbits();
        this.varpListener = e -> onChange(varpIds, e.varId(), e.newValue());
        this.varbitListener = e -> onChange(varbitIds, e.varId(), e.newValue());
        for (TrackerVar tracker : quest.trackers()) {
            cache.put(tracker.id(), 0);
        }
        eventBus.subscribe(VarChangeEvent.class, varpListener);
        eventBus.subscribe(VarbitChangeEvent.class, varbitListener);
        refresh();
    }

    /**
     * Returns the current tuple. Re-seeds first if the heartbeat budget has
     * elapsed since the last batch query.
     */
    public QuestState current() {
        if (System.currentTimeMillis() - lastHeartbeatMs > HEARTBEAT_MS) {
            refresh();
        }
        return new QuestState(snapshot());
    }

    /** Snapshot without forcing a refresh — for tests and assertions. */
    public QuestState peek() {
        return new QuestState(snapshot());
    }

    /** Force a batch re-seed regardless of heartbeat budget. */
    public void refresh() {
        List<Integer> varps = safeRead(varpIds, api::getVarps);
        seed(varpIds, i -> i < varps.size() ? varps.get(i) : null);
        List<VarbitValue> varbits = safeRead(varbitIds, api::queryVarbits);
        seed(varbitIds, i -> i < varbits.size() ? varbits.get(i).value() : null);
        lastHeartbeatMs = System.currentTimeMillis();
    }

    @Override
    public void close() {
        eventBus.unsubscribe(VarChangeEvent.class, varpListener);
        eventBus.unsubscribe(VarbitChangeEvent.class, varbitListener);
    }

    /** Stores each known answer; a missing or negative one keeps the last known value. */
    private void seed(int[] ids, IntFunction<Integer> answerAt) {
        for (int i = 0; i < ids.length; i++) {
            Integer answer = answerAt.apply(i);
            if (answer != null && answer >= 0) {
                cache.put(ids[i], answer);
            }
        }
    }

    private void onChange(int[] trackedOfThisKind, int varId, int newValue) {
        if (Arrays.stream(trackedOfThisKind).anyMatch(id -> id == varId)) {
            cache.put(varId, newValue);
        }
    }

    private Map<Integer, Integer> snapshot() {
        return new HashMap<>(cache);
    }

    private static <T> List<T> safeRead(int[] ids, Function<List<Integer>, List<T>> read) {
        if (ids.length == 0) {
            return List.of();
        }
        try {
            List<T> r = read.apply(Arrays.stream(ids).boxed().toList());
            return r == null ? List.of() : r;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
}
