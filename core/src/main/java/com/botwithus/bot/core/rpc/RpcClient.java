package com.botwithus.bot.core.rpc;

import com.botwithus.bot.core.msgpack.MessagePackCodec;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.pipe.PipeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.botwithus.bot.core.runtime.ConnectionContext;
import com.botwithus.bot.core.runtime.ScriptGate;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * RPC layer over the named pipe.
 *
 * <p>Windows named pipes with synchronous handles serialize all I/O on the
 * same handle — a blocked {@code ReadFile} prevents {@code WriteFile} from
 * another thread. To avoid deadlocks, all pipe access is protected by a
 * single {@link #pipeLock}.</p>
 *
 * <p>A background reader thread polls for incoming messages using
 * {@link PipeClient#available()} (which calls {@code PeekNamedPipe} on
 * Windows) so it never blocks on the pipe handle. The pipe carries only
 * RPC traffic now — game events are delivered via the shared-memory ring
 * (see {@code SharedRegionEventPump}). The reader's remaining job is to
 * detect a broken pipe and notify the disconnect handler.</p>
 */
public class RpcClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RpcClient.class);
    private final PipeClient pipe;
    private final AtomicInteger idCounter = new AtomicInteger(1);
    private final ReentrantLock pipeLock = new ReentrantLock();
    private final Condition dataAvailable = pipeLock.newCondition();
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rpc-watchdog");
                t.setDaemon(true);
                return t;
            });

    private Consumer<Throwable> disconnectHandler;
    private volatile boolean running;
    private String connectionName;
    private volatile ScriptGate scriptGate;

    /** Default per-call deadline before doCall gives up and throws RpcException. */
    private static final long DEFAULT_TIMEOUT_MS = 10_000L;

    /**
     * Reader idle re-poll interval. Bounds worst-case latency for unsolicited
     * server events when no RPC is in flight while keeping the syscall rate
     * well below the scheduler's resolution.
     */
    private static final long READER_IDLE_WAIT_NANOS = 1_000_000L;

    private long timeoutMs = DEFAULT_TIMEOUT_MS;
    private RetryPolicy retryPolicy = RetryPolicy.NONE;
    private final RpcMetrics metrics = new RpcMetrics();

    public RpcClient(PipeClient pipe) {
        this.pipe = pipe;
    }

    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }

    public String getConnectionName() {
        return connectionName;
    }

    public void setTimeout(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long getTimeout() {
        return timeoutMs;
    }

    public void setRetryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
    }

    public RpcMetrics getMetrics() {
        return metrics;
    }

    /**
     * Optional callback invoked when the reader loop detects the pipe has
     * been closed/broken. Receives the originating exception (may be null).
     */
    public void setDisconnectHandler(Consumer<Throwable> handler) {
        this.disconnectHandler = handler;
    }

    /**
     * Installs the per-connection {@link ScriptGate} consulted before every RPC.
     * When unset (tests, headless seams) no revocation check happens and every
     * caller is allowed through.
     *
     * <p>Must be the same instance the connection's {@code ScriptRuntime} was
     * given, or revocations raised by the watchdog won't be seen here.</p>
     */
    public void setScriptGate(ScriptGate scriptGate) {
        this.scriptGate = scriptGate;
    }

    /**
     * Swaps the underlying pipe transport to point at {@code pipeName} and
     * restarts the reader loop. Used by {@code ReconnectController} after the
     * remote agent comes back. Acquires {@link #pipeLock} for the duration of
     * the swap so no concurrent send/read can observe a half-open transport.
     *
     * @throws com.botwithus.bot.core.pipe.PipeException if the new transport
     *         fails to open; the previous transport remains untouched.
     */
    public void reconnect(String pipeName) {
        pipeLock.lock();
        try {
            pipe.reconnect(pipeName);
        } finally {
            pipeLock.unlock();
        }
        start();
    }

    /**
     * Starts the background reader thread. Must be called before any RPC calls.
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        String connName = this.connectionName;
        // Platform, not virtual: this is the thread every RPC response arrives
        // on, so it must never be starved by whatever else is running. A
        // virtual reader shares its carrier pool with script threads, and a
        // CPU-bound script (which is never preempted off a carrier) would
        // otherwise stall RPC for every connected client. See ScriptRunner's
        // rule-exception note and CLAUDE.md "Java rules exceptions".
        Thread.ofPlatform().name("rpc-reader").daemon(true).start(() -> {
            if (connName != null) {
                ConnectionContext.set(connName);
            }
            readerLoop();
        });
    }

    /**
     * Synchronous RPC call. Returns the {@code "result"} field as a Map.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callSync(String method, Map<String, Object> params) {
        Map<String, Object> response = doCallWithRetry(method, params);

        if (response.containsKey("error") && response.get("error") != null) {
            throw new RpcException("RPC error: " + response.get("error"));
        }
        Object result = response.get("result");
        // rule-exception: {rule:no-instanceof} and {rule:no-casts} — wire-decode boundary.
        // RpcClient is the msgpack layer; result is Object because the codec returns mixed
        // types. callSync's contract guarantees Map<String, Object> when the producer wraps
        // its result in a map, so this is the single recovery seam for non-Map producers.
        if (result instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of("value", result != null ? result : Map.of());
    }

    /**
     * Synchronous call that returns the raw result value (may be Map, List, or primitive).
     */
    public Object callSyncRaw(String method, Map<String, Object> params) {
        Map<String, Object> response = doCallWithRetry(method, params);

        if (response.containsKey("error") && response.get("error") != null) {
            throw new RpcException("RPC error: " + response.get("error"));
        }
        return response.get("result");
    }

    /**
     * Synchronous call for methods that return an array result.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> callSyncList(String method, Map<String, Object> params) {
        Object raw = callSyncRaw(method, params);
        // rule-exception: {rule:no-instanceof} and {rule:no-casts} — wire-decode boundary;
        // same justification as callSync. callSyncList is the typed seam for array-returning
        // RPC methods; the cast is one-per-shape at this single recovery site.
        if (raw instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    @Override
    public void close() {
        running = false;
        watchdog.shutdownNow();
        pipe.close();
    }

    // ========================== Internal ==========================

    /**
     * Background reader loop. Polls {@link PipeClient#available()} to check
     * for data without blocking the pipe handle. When data is available and
     * no RPC call holds the lock, reads and dispatches events.
     *
     * <p>Idle-wait strategy: waits on the {@link #dataAvailable} condition
     * with a short bounded timeout so we still re-check {@code available()}
     * periodically for server-pushed events that arrive without a
     * corresponding RPC. {@link #doCall} signals the condition before
     * releasing the lock, so the reader wakes immediately after any RPC
     * round-trip and picks up buffered events with no extra delay.</p>
     */
    private void readerLoop() {
        Throwable disconnectCause = null;
        while (running && pipe.isOpen()) {
            try {
                if (pipe.available() > 0 && pipeLock.tryLock()) {
                    try {
                        drainStrayMessages();
                    } finally {
                        pipeLock.unlock();
                    }
                } else {
                    awaitDataOrIdle();
                }
            } catch (PipeException e) {
                disconnectCause = e;
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running) {
                    log.error("Reader error: {}", e.getMessage());
                }
            }
        }

        boolean wasRunning = running;
        running = false;
        if (wasRunning) {
            notifyDisconnect(disconnectCause);
        }
    }

    /**
     * Drain stray messages from the pipe — anything visible to the reader is
     * traffic not claimed by an in-flight doCall, so the kernel buffer must
     * not be allowed to fill. Caller holds {@code pipeLock}.
     */
    private void drainStrayMessages() throws PipeException {
        while (pipe.available() > 0) {
            pipe.readMessage();
        }
    }

    /**
     * Sleep on {@code dataAvailable} with the re-poll interval. Re-checks
     * {@code pipe.available()} under the lock to avoid a lost-wakeup race
     * between the available() probe and the await.
     */
    private void awaitDataOrIdle() throws InterruptedException {
        pipeLock.lock();
        try {
            if (pipe.available() == 0) {
                dataAvailable.awaitNanos(READER_IDLE_WAIT_NANOS);
            }
        } finally {
            pipeLock.unlock();
        }
    }

    private void notifyDisconnect(Throwable disconnectCause) {
        Consumer<Throwable> cb = this.disconnectHandler;
        if (cb == null) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try { cb.accept(disconnectCause); } catch (RuntimeException ex) {
                log.warn("disconnectHandler threw: {}", ex.getMessage());
            }
        });
    }

    /**
     * Sends the request, then reads messages until the response with the
     * matching ID arrives. Holds the pipe lock for the entire duration.
     *
     * <p>Timeout enforcement uses a scheduled watchdog task that forcibly
     * closes the pipe when the deadline expires. That unblocks any pending
     * blocking {@code ReadFile} with an IOException, which is then translated
     * to {@link RpcTimeoutException}. Without the watchdog, a server that
     * simply stops responding would hang {@code readMessage()} forever.</p>
     */
    private Map<String, Object> doCall(String method, Map<String, Object> params) {
        int id = idCounter.getAndIncrement();
        Map<String, Object> request = encodeRequest(method, id, params);

        pipeLock.lock();
        // settled wins the race between the watchdog and the completing call
        // path: whichever CASes it from false -> true takes ownership of the
        // "what happened" decision. Prevents a late-firing watchdog from
        // closing the pipe after a successful response.
        final AtomicBoolean settled = new AtomicBoolean(false);
        ScheduledFuture<?> watchdogTask = null;
        try {
            pipe.send(MessagePackCodec.encode(request));
            watchdogTask = scheduleWatchdog(settled);
            return awaitMatchingResponse(method, id, settled);
        } catch (RpcException e) {
            throw e;
        } catch (Exception e) {
            throw new RpcException("RPC call failed: " + method, e);
        } finally {
            // Claim the outcome if neither read-success nor watchdog did.
            settled.compareAndSet(false, true);
            if (watchdogTask != null) {
                watchdogTask.cancel(false);
            }
            // Signal reader thread that the lock is about to be released
            dataAvailable.signal();
            pipeLock.unlock();
        }
    }

    private static Map<String, Object> encodeRequest(String method, int id, Map<String, Object> params) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", method);
        request.put("id", id);
        if (params != null && !params.isEmpty()) {
            request.put("params", params);
        }
        return request;
    }

    private ScheduledFuture<?> scheduleWatchdog(AtomicBoolean settled) {
        return watchdog.schedule(() -> {
            if (settled.compareAndSet(false, true)) {
                try {
                    pipe.close();
                } catch (RuntimeException e) {
                    log.debug("watchdog pipe.close threw", e);
                }
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Reads pipe messages until one with the matching id arrives. A
     * {@link PipeException} after the watchdog has already CAS'd settled is
     * translated to {@link RpcTimeoutException}; otherwise it propagates.
     */
    private Map<String, Object> awaitMatchingResponse(String method, int id, AtomicBoolean settled) throws PipeException {
        while (true) {
            byte[] responseBytes;
            try {
                responseBytes = pipe.readMessage();
            } catch (PipeException e) {
                if (!settled.compareAndSet(false, true)) {
                    throw new RpcTimeoutException(method, timeoutMs);
                }
                throw e;
            }
            Map<String, Object> msg = MessagePackCodec.decode(responseBytes);
            if (matchesId(msg, id)) {
                settled.set(true);
                return msg;
            }
            // Wrong id (stale response, mismatched call) — skip it
        }
    }

    private Map<String, Object> doCallWithRetry(String method, Map<String, Object> params) {
        // Single gate for the whole game-facing surface: all three callSync*
        // methods funnel through here. Checked before the retry loop and before
        // pipeLock, so a revoked script neither retries (it can never succeed)
        // nor contends for the pipe with live scripts. ScriptRevokedException
        // is not an RpcException, so the catch arms below don't swallow it.
        ScriptGate gate = this.scriptGate;
        if (gate != null) {
            gate.checkCaller();
        }
        RpcException lastException = null;
        int attempts = 1 + retryPolicy.maxRetries();
        for (int i = 0; i < attempts; i++) {
            long startNanos = System.nanoTime();
            boolean error = false;
            try {
                Map<String, Object> result = doCall(method, params);
                return result;
            } catch (RpcTimeoutException e) {
                error = true;
                metrics.recordCall(method, System.nanoTime() - startNanos, true);
                throw e;
            } catch (RpcException e) {
                error = true;
                lastException = e;
                if (i < attempts - 1) {
                    long delay = retryPolicy.delayForAttempt(i + 1);
                    try { Thread.sleep(delay); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            } finally {
                if (!error) {
                    metrics.recordCall(method, System.nanoTime() - startNanos, false);
                } else if (lastException != null && i == attempts - 1) {
                    metrics.recordCall(method, System.nanoTime() - startNanos, true);
                }
            }
        }
        throw lastException;
    }

    private boolean matchesId(Map<String, Object> msg, int expectedId) {
        Object idObj = msg.get("id");
        // rule-exception: {rule:no-instanceof} — wire-decode boundary. RPC response IDs
        // arrive as msgpack ints decoded to Integer or Long; the producer's id field is
        // Object until we recover it here.
        if (idObj instanceof Number n) {
            return n.intValue() == expectedId;
        }
        return false;
    }
}
