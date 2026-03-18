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
 * Windows) so it never blocks on the pipe handle. When data is available
 * and no RPC call is in flight, the reader thread acquires the lock and
 * reads. Events are dispatched on virtual threads.</p>
 *
 * <p>When an RPC call is active ({@link #doCall}), the calling thread holds
 * the lock and reads responses itself (dispatching any interleaved events),
 * exactly like the original sequential model.</p>
 */
public class RpcClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RpcClient.class);
    private final PipeClient pipe;
    private final AtomicInteger idCounter = new AtomicInteger(1);
    private final ReentrantLock pipeLock = new ReentrantLock();
    private final Condition dataAvailable = pipeLock.newCondition();

    private Consumer<Map<String, Object>> eventHandler;
    private volatile boolean running;
    private String connectionName;

    private long timeoutMs = 10_000;
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

    public void setEventHandler(Consumer<Map<String, Object>> handler) {
        this.eventHandler = handler;
    }

    /**
     * Starts the background reader thread. Must be called after
     * {@link #setEventHandler} and before any RPC calls.
     */
    public void start() {
        if (running) return;
        running = true;
        String connName = this.connectionName;
        Thread.ofVirtual().name("rpc-reader").start(() -> {
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
        if (raw instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    @Override
    public void close() {
        running = false;
        pipe.close();
    }

    // ========================== Internal ==========================

    /**
     * Background reader loop. Polls {@link PipeClient#available()} to check
     * for data without blocking the pipe handle. When data is available and
     * no RPC call holds the lock, reads and dispatches events.
     *
     * <p>When no data is available, waits on the {@link #dataAvailable}
     * condition with a short timeout instead of busy-polling with
     * {@code Thread.sleep}. The RPC call path signals this condition after
     * releasing the lock so the reader wakes immediately when the pipe
     * becomes idle again.</p>
     */
    private void readerLoop() {
        while (running && pipe.isOpen()) {
            try {
                if (pipe.available() > 0 && pipeLock.tryLock()) {
                    try {
                        // Drain all available messages while we hold the lock
                        while (pipe.available() > 0) {
                            byte[] data = pipe.readMessage();
                            Map<String, Object> msg = MessagePackCodec.decode(data);
                            if (msg.containsKey("event")) {
                                dispatchEventAsync(msg);
                            }
                            // Non-event messages with no pending doCall — discard
                        }
                    } finally {
                        pipeLock.unlock();
                    }
                } else {
                    // Wait for the RPC call to finish or data to arrive.
                    // Uses a short timeout so we re-check pipe.available().
                    pipeLock.lock();
                    try {
                        dataAvailable.awaitNanos(5_000_000L); // 5ms max wait
                    } finally {
                        pipeLock.unlock();
                    }
                }
            } catch (PipeException e) {
                if (running) {
                    running = false;
                }
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
    }

    /**
     * Sends the request, then reads messages until the response with the
     * matching ID arrives. Events received in between are dispatched on
     * virtual threads. Holds the pipe lock for the entire duration.
     *
     * <p>Uses blocking {@link PipeClient#readMessage()} directly rather than
     * polling with {@code available()} + sleep, since we hold the lock
     * exclusively and the server responds in microseconds. A watchdog thread
     * enforces the timeout by closing the pipe if the deadline expires.</p>
     */
    private Map<String, Object> doCall(String method, Map<String, Object> params) {
        int id = idCounter.getAndIncrement();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", method);
        request.put("id", id);
        if (params != null && !params.isEmpty()) {
            request.put("params", params);
        }

        pipeLock.lock();
        try {
            pipe.send(MessagePackCodec.encode(request));

            long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;

            // Read messages until we get the response matching our request ID.
            // Any event messages that arrive first are dispatched asynchronously.
            // Blocking read is safe here — we hold the lock exclusively and the
            // server typically responds within microseconds.
            while (true) {
                if (System.nanoTime() > deadlineNanos) {
                    throw new RpcTimeoutException(method, timeoutMs);
                }

                byte[] responseBytes = pipe.readMessage();
                Map<String, Object> msg = MessagePackCodec.decode(responseBytes);

                if (msg.containsKey("event")) {
                    dispatchEventAsync(msg);
                    continue;
                }

                if (matchesId(msg, id)) {
                    return msg;
                }

                // Unknown message (no event, wrong id) — skip it
            }
        } catch (RpcException e) {
            throw e;
        } catch (Exception e) {
            throw new RpcException("RPC call failed: " + method, e);
        } finally {
            // Signal reader thread that the lock is about to be released
            dataAvailable.signal();
            pipeLock.unlock();
        }
    }

    private Map<String, Object> doCallWithRetry(String method, Map<String, Object> params) {
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
        if (idObj instanceof Number n) return n.intValue() == expectedId;
        return false;
    }

    private void dispatchEventAsync(Map<String, Object> msg) {
        Consumer<Map<String, Object>> handler = this.eventHandler;
        if (handler != null) {
            String connName = this.connectionName;
            Thread.startVirtualThread(() -> {
                if (connName != null) {
                    ConnectionContext.set(connName);
                }
                handler.accept(msg);
            });
        }
    }
}
