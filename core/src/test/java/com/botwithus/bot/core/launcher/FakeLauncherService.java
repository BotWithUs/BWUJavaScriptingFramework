package com.botwithus.bot.core.launcher;

import com.botwithus.bot.core.launcher.FrameChannel.ChannelClosedException;
import org.msgpack.value.MapValue;
import org.msgpack.value.ValueFactory.MapBuilder;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * An in-process stand-in for the launcher service's automation pipe. Each
 * {@link #opener() open} is a new connection; requests are answered
 * synchronously from per-method handlers, and events are pushed with a
 * per-connection sequence, as the service does.
 */
final class FakeLauncherService {

    /** What a handler answers: a body, an error, or nothing at all. */
    sealed interface Reply {
        record Ok(Value body) implements Reply { }
        record Error(String code, String message, boolean isRetryable, Value detail) implements Reply { }
        record Silence() implements Reply { }
    }

    /** One request the fake received. */
    record Received(int connection, String method, Value body) { }

    private final Map<String, Function<Value, Reply>> handlers = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger opens = new AtomicInteger();
    private volatile boolean isUp = true;
    private volatile boolean isFrozen;
    private volatile Connection current;
    private volatile Runnable afterHello = () -> { };

    FakeLauncherService() {
        handle(LauncherProtocol.METHOD_HELLO, body -> ok(body().put("protocolVersion", 1)
                .put("serviceVersion", "fake").put("surface", "automation")
                .put("methods", ValueFactory.newArray()).put("events", ValueFactory.newArray())
                .put("connectionId", opens.get()).build()));
        handle(LauncherProtocol.METHOD_EVENTS_SUBSCRIBE,
                body -> ok(body().put("snapshot", ValueFactory.emptyMap()).build()));
        handle(LauncherProtocol.METHOD_SERVICE_STATUS, body -> ok(ValueFactory.emptyMap()));
    }

    FrameChannel.Opener opener() {
        return () -> {
            if (!isUp) {
                throw new ChannelClosedException("fake service is down");
            }
            Connection c = new Connection(opens.incrementAndGet());
            current = c;
            return c;
        };
    }

    void handle(String method, Function<Value, Reply> handler) {
        handlers.put(method, handler);
    }

    /** Runs once after each {@code hello} reply is queued, before any other request is answered. */
    void afterHello(Runnable action) {
        afterHello = action;
    }

    /** Stops answering and accepting connections; the open one breaks. */
    void goDown() {
        isUp = false;
        breakConnection();
    }

    void comeBack() {
        isUp = true;
    }

    /** Breaks the open connection, as a killed service does. */
    void breakConnection() {
        Connection c = current;
        if (c != null) {
            c.close();
        }
    }

    /** Keeps the connection open but never answers again: a pipe whose peer died unseen. */
    void freeze() {
        isFrozen = true;
    }

    void thaw() {
        isFrozen = false;
    }

    void pushEvent(String method, Value body) {
        Connection c = current;
        c.push(method, body, c.seq.incrementAndGet());
    }

    void pushEventWithSeq(String method, Value body, long seq) {
        Connection c = current;
        c.seq.set(seq);
        c.push(method, body, seq);
    }

    int opens() {
        return opens.get();
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    List<Received> received(String method) {
        return received.stream().filter(r -> r.method().equals(method)).toList();
    }

    static Reply ok(Value body) {
        return new Reply.Ok(body);
    }

    static Reply error(String code) {
        return new Reply.Error(code, code + " from the fake", false, ValueFactory.emptyMap());
    }

    /** @return a new map builder; keys keep the order they are put in */
    static Body body() {
        return new Body();
    }

    /** A typed, ordered msgpack map builder for test bodies. */
    static final class Body {

        private final MapBuilder builder = ValueFactory.newMapBuilder();

        Body put(String key, String value) {
            return put(key, ValueFactory.newString(value));
        }

        Body put(String key, long value) {
            return put(key, ValueFactory.newInteger(value));
        }

        Body put(String key, boolean value) {
            return put(key, ValueFactory.newBoolean(value));
        }

        Body put(String key, Value value) {
            builder.put(ValueFactory.newString(key), value);
            return this;
        }

        MapValue build() {
            return builder.build();
        }
    }

    /** Waits until {@code condition} holds or fails the test. */
    static void await(String what, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            try {
                Thread.sleep(Duration.ofMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** One connection, as the host sees it. */
    private final class Connection implements FrameChannel {

        private final int number;
        private final ConcurrentLinkedDeque<byte[]> inbound = new ConcurrentLinkedDeque<>();
        private final AtomicLong seq = new AtomicLong();
        private volatile boolean isOpen = true;

        Connection(int number) {
            this.number = number;
        }

        @Override
        public void send(byte[] body) {
            if (!isOpen) {
                throw new ChannelClosedException("fake connection closed");
            }
            Envelope request = Envelope.decode(body);
            received.add(new Received(number, request.method(), request.body()));
            if (isFrozen) {
                return;
            }
            Reply reply = handlers.getOrDefault(request.method(), b -> error("unknown_method")).apply(request.body());
            respond(request, reply);
            if (request.method().equals(LauncherProtocol.METHOD_HELLO)) {
                afterHello.run();
            }
        }

        private void respond(Envelope request, Reply reply) {
            Body envelope = body().put("v", 1).put("id", request.id()).put("kind", "resp")
                    .put("method", request.method());
            switch (reply) {
                case Reply.Ok ok -> envelope.put("ok", true).put("body", ok.body());
                case Reply.Error e -> envelope.put("ok", false).put("body", ValueFactory.emptyMap())
                        .put("error", body().put("code", e.code()).put("message", e.message())
                                .put("retryable", e.isRetryable()).put("detail", e.detail()).build());
                case Reply.Silence s -> {
                    return;
                }
            }
            inbound.add(LauncherRequests.pack(envelope.build()));
        }

        void push(String method, Value body, long sequence) {
            inbound.add(LauncherRequests.pack(body().put("v", 1).put("id", 0).put("kind", "event")
                    .put("method", method).put("body", body).put("seq", sequence).build()));
        }

        @Override
        public int available() {
            byte[] next = inbound.peekFirst();
            return isOpen && next != null ? next.length : 0;
        }

        @Override
        public byte[] read() {
            byte[] next = inbound.pollFirst();
            if (!isOpen || next == null) {
                throw new ChannelClosedException("fake connection closed");
            }
            return next;
        }

        @Override
        public boolean isOpen() {
            return isOpen;
        }

        @Override
        public void close() {
            isOpen = false;
        }
    }
}
