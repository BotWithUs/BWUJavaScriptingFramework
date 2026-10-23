package com.botwithus.bot.core.launcher;

import com.botwithus.bot.core.launcher.FrameChannel.ChannelClosedException;
import org.msgpack.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One open connection to the service: the channel, the thread that reads it,
 * and the requests waiting for their responses.
 *
 * <p>Responses may arrive in any order and interleave with events (ADR 2.4),
 * so a request is matched to its response by {@code id}. Only the reader
 * thread reads; a caller writes under the same lock and then waits on its own
 * future. The reader reads only when the channel says a frame is waiting, as
 * {@code RpcClient} does, because a pipe opened for synchronous I/O deadlocks
 * when one thread blocks reading while another writes.</p>
 *
 * <p>Any failure closes the session: a broken channel, a malformed frame, or a
 * call that got no answer in time. After that the connection cannot be
 * trusted, and {@link LauncherService} opens a new one.</p>
 */
final class ServiceSession implements AutoCloseable {

    /** What the session reports to its owner. Called on the reader thread; must not block. */
    interface Listener {

        /**
         * @param source the session the event arrived on
         * @param event  an event envelope
         * @param missed how many events the sequence skipped just before this one
         */
        void onEvent(ServiceSession source, Envelope event, long missed);

        /** The session closed; called once. */
        void onClosed(ServiceSession session, Throwable cause);
    }

    private static final Logger log = LoggerFactory.getLogger(ServiceSession.class);

    private final FrameChannel channel;
    private final Duration readPoll;
    private final Listener listener;
    private final ReentrantLock io = new ReentrantLock();
    private final Map<Long, CompletableFuture<Envelope>> pending = new ConcurrentHashMap<>();
    private final AtomicLong lastId = new AtomicLong();
    private final AtomicBoolean isClosed = new AtomicBoolean();
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile long lastActivityNanos = System.nanoTime();
    private long lastSeq;

    ServiceSession(FrameChannel channel, Duration readPoll, Listener listener) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.readPoll = Objects.requireNonNull(readPoll, "readPoll");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Starts the reader thread. */
    void start() {
        Thread.ofVirtual().name("launcher-service-reader").start(this::readLoop);
    }

    /**
     * Sends a request and waits for its response.
     *
     * @return the response envelope, whether or not it is {@code ok}
     * @throws ChannelClosedException if the session is or becomes closed, or no
     *                                answer came within {@code timeout}; the
     *                                session is closed in every such case
     */
    Envelope call(String method, Value body, Duration timeout) {
        if (isClosed.get()) {
            throw new ChannelClosedException("the connection to the service is closed");
        }
        long id = nextId();
        CompletableFuture<Envelope> response = new CompletableFuture<>();
        pending.put(id, response);
        send(id, LauncherRequests.encode(id, method, body));
        return await(id, method, response, timeout);
    }

    /** @return how long since a frame was last sent or received */
    Duration idleFor() {
        return Duration.ofNanos(System.nanoTime() - lastActivityNanos);
    }

    /** @return {@code true} once closed, waiting up to {@code timeout} */
    boolean awaitClosed(Duration timeout) throws InterruptedException {
        return closed.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    boolean isClosed() {
        return isClosed.get();
    }

    @Override
    public void close() {
        closeWith(new ChannelClosedException("closed by the host"));
    }

    private long nextId() {
        return lastId.updateAndGet(id -> id >= LauncherProtocol.MAX_REQUEST_ID ? 1 : id + 1);
    }

    private void send(long id, byte[] frame) {
        io.lock();
        try {
            channel.send(frame);
            lastActivityNanos = System.nanoTime();
        } catch (ChannelClosedException e) {
            pending.remove(id);
            closeWith(e);
            throw e;
        } finally {
            io.unlock();
        }
    }

    private Envelope await(long id, String method, CompletableFuture<Envelope> response, Duration timeout) {
        try {
            return response.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            ChannelClosedException noAnswer = new ChannelClosedException(
                    "the service did not answer " + method + " within " + timeout.toSeconds() + " s", e);
            closeWith(noAnswer);
            throw noAnswer;
        } catch (ExecutionException e) {
            throw new ChannelClosedException("the connection closed during " + method, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelClosedException("interrupted waiting for " + method, e);
        } finally {
            pending.remove(id);
        }
    }

    private void readLoop() {
        try {
            while (!isClosed.get()) {
                List<byte[]> frames = drainWaitingFrames();
                if (frames.isEmpty()) {
                    Thread.sleep(readPoll);
                }
                frames.forEach(this::dispatch);
            }
        } catch (ChannelClosedException | MalformedFrameException e) {
            closeWith(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeWith(e);
        }
    }

    private List<byte[]> drainWaitingFrames() {
        List<byte[]> frames = new ArrayList<>();
        io.lock();
        try {
            while (channel.isOpen() && channel.available() > 0) {
                frames.add(channel.read());
            }
            if (!channel.isOpen()) {
                throw new ChannelClosedException("the channel closed");
            }
        } finally {
            io.unlock();
        }
        return frames;
    }

    private void dispatch(byte[] frame) {
        Envelope envelope = Envelope.decode(frame);
        lastActivityNanos = System.nanoTime();
        switch (envelope.kind()) {
            case RESPONSE -> complete(envelope);
            case EVENT -> listener.onEvent(this, envelope, missedBefore(envelope.seq()));
            case REQUEST, UNKNOWN -> log.debug("Ignoring a {} frame from the service", envelope.kind());
        }
    }

    private void complete(Envelope response) {
        CompletableFuture<Envelope> waiting = pending.get(response.id());
        if (waiting == null) {
            log.debug("Response {} to {} matches no waiting request", response.id(), response.method());
            return;
        }
        waiting.complete(response);
    }

    /** Sequence numbers start at 1 per connection and rise by one per event (ADR 2.4). */
    private long missedBefore(long seq) {
        long expected = lastSeq + 1;
        lastSeq = Math.max(lastSeq, seq);
        return seq > expected ? seq - expected : 0;
    }

    private void closeWith(Throwable cause) {
        if (!isClosed.compareAndSet(false, true)) {
            return;
        }
        channel.close();
        ChannelClosedException failure = new ChannelClosedException("the connection to the service closed", cause);
        pending.values().forEach(f -> f.completeExceptionally(failure));
        closed.countDown();
        listener.onClosed(this, cause);
    }
}
