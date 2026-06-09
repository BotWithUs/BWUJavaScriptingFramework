package com.botwithus.bot.quest;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.api.event.EventBus;
import com.botwithus.bot.api.event.VarChangeEvent;
import com.botwithus.bot.api.event.VarbitChangeEvent;
import com.botwithus.bot.api.model.VarbitValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Per-quest cache of tracker-var values. Updates incrementally from
 * {@link VarChangeEvent} / {@link VarbitChangeEvent} on the bus, and re-seeds
 * the entire tuple from a batch RPC every {@link #HEARTBEAT_MS} ms — the
 * event stream is a best-effort signal (single dropped delta strands the
 * tracker on a stale value), so we always have a self-healing path.
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

    private final QuestId quest;
    private final GameAPI api;
    private final EventBus eventBus;
    private final Consumer<VarChangeEvent> varpListener;
    private final Consumer<VarbitChangeEvent> varbitListener;
    private final Map<Integer, Integer> cache = new ConcurrentHashMap<>();
    private volatile long lastHeartbeatMs;

    public QuestProgressTracker(QuestId quest, ScriptContext ctx) {
        this(quest, ctx.getGameAPI(), ctx.getEventBus());
    }

    /** Constructor for tests that wire {@link GameAPI} and {@link EventBus} directly. */
    public QuestProgressTracker(QuestId quest, GameAPI api, EventBus eventBus) {
        this.quest = Objects.requireNonNull(quest, "quest");
        this.api = Objects.requireNonNull(api, "api");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.varpListener = e -> onChange(e.varId(), e.newValue());
        this.varbitListener = e -> onChange(e.varId(), e.newValue());
        for (int id : quest.trackerVars()) {
            cache.put(id, 0);
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
        int[] ids = quest.trackerVars();
        if (ids.length == 0) {
            lastHeartbeatMs = System.currentTimeMillis();
            return;
        }
        List<Integer> idList = new ArrayList<>(ids.length);
        for (int id : ids) {
            idList.add(id);
        }
        List<Integer> varps = safeVarps(idList);
        List<VarbitValue> varbits = safeVarbits(idList);
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i];
            int varpV = i < varps.size() ? varps.get(i) : -1;
            int varbitV = i < varbits.size() ? varbits.get(i).value() : -1;
            int chosen;
            if (varpV >= 0) {
                chosen = varpV;
            } else if (varbitV >= 0) {
                chosen = varbitV;
            } else {
                chosen = cache.getOrDefault(id, 0);
            }
            cache.put(id, chosen);
        }
        lastHeartbeatMs = System.currentTimeMillis();
    }

    @Override
    public void close() {
        eventBus.unsubscribe(VarChangeEvent.class, varpListener);
        eventBus.unsubscribe(VarbitChangeEvent.class, varbitListener);
    }

    private void onChange(int varId, int newValue) {
        if (!isTracked(varId)) {
            return;
        }
        cache.put(varId, newValue);
    }

    private boolean isTracked(int varId) {
        for (int id : quest.trackerVars()) {
            if (id == varId) {
                return true;
            }
        }
        return false;
    }

    private Map<Integer, Integer> snapshot() {
        return new HashMap<>(cache);
    }

    private List<Integer> safeVarps(List<Integer> ids) {
        try {
            List<Integer> r = api.getVarps(ids);
            return r == null ? List.of() : r;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<VarbitValue> safeVarbits(List<Integer> ids) {
        try {
            List<VarbitValue> r = api.queryVarbits(ids);
            return r == null ? List.of() : r;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
}
