package com.botwithus.bot.core.impl;

import com.botwithus.bot.api.debug.ScriptContextPublisher;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-connection broker channel for the {@code script.context} topic. Owns a
 * bounded queue and one virtual worker thread that forwards each enqueued
 * payload to the agent via {@code _debug.publish}.
 *
 * <p>Callers don't publish through this class directly — they obtain a
 * per-script {@link ScriptContextPublisher} via {@link #publisherFor(String)},
 * which builds the {@code {script, connection, t_us, kind, ...}} envelope and
 * hands it to {@link #enqueue}.</p>
 *
 * <p>The queue caps at {@link #QUEUE_CAPACITY}. On overflow the oldest entry
 * is dropped (matching the agent's per-pipe push queue semantics) and the
 * drop counter advances; nothing throws back to the caller. The publish
 * worker swallows {@link RpcException}s — a disconnected pipe means the
 * debugger isn't around to listen anyway and there's nothing the script can
 * do about it.</p>
 *
 * <p>Lifetime is bound to its owning connection: instantiate after
 * {@link RpcClient#start} succeeds, call {@link #close()} during connection
 * shutdown.</p>
 */
public class ScriptContextChannel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ScriptContextChannel.class);

    /** Wire topic. Matches the broker subscription on the NXTDebugger side. */
    public static final String TOPIC = "script.context";

    /**
     * Per-connection bounded backlog. 256 frames at 1 KB each is 256 KB worst
     * case — well below the per-process RAM budget, but large enough to absorb
     * a debugger reconnect or a busy script that briefly bursts.
     */
    private static final int QUEUE_CAPACITY = 256;

    private final RpcClient rpc;
    private final String connectionName;
    private final LinkedBlockingDeque<Map<String, Object>> queue =
            new LinkedBlockingDeque<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;

    public ScriptContextChannel(RpcClient rpc, String connectionName) {
        this.rpc = rpc;
        this.connectionName = connectionName;
        String threadName = "script-ctx-" + (connectionName != null ? connectionName : "?");
        this.worker = Thread.ofVirtual().name(threadName).start(this::workerLoop);
    }

    /**
     * Returns a publisher tagged with {@code scriptName}. Every call into the
     * returned publisher stamps that name into the envelope's {@code script}
     * field before enqueueing.
     */
    public ScriptContextPublisher publisherFor(String scriptName) {
        return new TaggedPublisher(this, scriptName);
    }

    /** Total payloads dropped due to queue overflow since construction. */
    public long droppedCount() {
        return dropped.get();
    }

    /** Current backlog size. Diagnostic only. */
    public int backlogSize() {
        return queue.size();
    }

    public String getConnectionName() {
        return connectionName;
    }

    void enqueue(Map<String, Object> data) {
        if (!running.get()) {
            return;
        }
        // Evict-and-retry under contention: with several script vthreads
        // publishing and one worker draining, a single pollFirst+offerLast is
        // racy (the freed slot can be refilled before our offer, silently
        // dropping data without accounting, or two publishers can both evict).
        // Loop until our payload lands, counting each eviction.
        while (!queue.offerLast(data)) {
            if (queue.pollFirst() != null) {
                dropped.incrementAndGet();
            }
        }
    }

    private void workerLoop() {
        while (running.get()) {
            Map<String, Object> data;
            try {
                data = queue.poll(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (data == null) {
                continue;
            }
            try {
                Map<String, Object> params = new LinkedHashMap<>(2);
                params.put("topic", TOPIC);
                params.put("data", data);
                rpc.callSync("_debug.publish", params);
            } catch (RpcException e) {
                if (running.get()) {
                    log.debug("script.context publish failed: {}", e.getMessage());
                }
            } catch (RuntimeException e) {
                log.warn("script.context worker swallowed: {}", e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        worker.interrupt();
        try {
            worker.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        queue.clear();
    }
}
