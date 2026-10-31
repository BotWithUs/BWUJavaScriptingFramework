package com.botwithus.bot.core.voice;

import com.botwithus.bot.api.voice.Heard;
import com.botwithus.bot.api.voice.VoiceInput;
import com.botwithus.bot.api.voice.VoiceSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The host's {@link VoiceInput}: one recogniser shared by every script.
 *
 * <p><b>Threads.</b> The engine is driven from one platform thread, {@code voice-engine},
 * started on first use. It has to be a platform thread: the engine is a COM object and COM
 * binds an apartment to an OS thread, which a virtual thread does not keep. Callbacks run on
 * a second platform thread, {@code voice-callback}, so a slow callback never delays the
 * engine and never runs on a script's loop thread. Neither thread inherits the inheritable
 * thread-locals of the script that happened to start it, so work done from a callback is not
 * attributed to that script.</p>
 *
 * <p><b>State.</b> {@code listen} and {@code close} only record which session is wanted and
 * wake the engine thread. The engine thread compares the wanted session with the one it has
 * applied and brings the engine into line, so overlapping calls from several threads
 * converge on the last one without locking around native calls. While nothing is wanted the
 * engine thread blocks, the engine is released and the microphone is closed.</p>
 */
public final class VoiceInputService implements VoiceInput, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VoiceInputService.class);

    /** How long the engine thread waits on the engine before checking for new requests. */
    static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    /** How long {@link #available()} waits for the engine thread to answer. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    /** How long {@link #close()} waits for the engine thread to release the engine. */
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final RecognizerBackend backend;
    private final BlockingQueue<FutureTask<?>> tasks = new LinkedBlockingQueue<>();
    private final AtomicReference<Session> wanted = new AtomicReference<>();
    private final Object threadLock = new Object();
    /** Guarded by {@link #threadLock}; null until first use. */
    private Thread engineThread;
    /** Guarded by {@link #threadLock}; null until the first recognition is delivered. */
    private ExecutorService callbacks;
    /** Written under {@link #threadLock}, so a task is never queued after the engine drained the queue. */
    private volatile boolean closed;

    VoiceInputService(RecognizerBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /**
     * Voice input from the Windows default microphone through the speech engine Windows
     * ships (SAPI). Building it starts nothing.
     */
    public static VoiceInputService windowsDefaultMicrophone() {
        return windows(AudioSource.defaultMicrophone());
    }

    /** Voice input from {@code source} through the Windows speech engine. Starts nothing. */
    public static VoiceInputService windows(AudioSource source) {
        return new VoiceInputService(new SapiRecognizer(source));
    }

    @Override
    public boolean available() {
        if (closed) {
            return false;
        }
        FutureTask<Boolean> probe = new FutureTask<>(backend::probe);
        if (!post(probe)) {
            return false;
        }
        try {
            return probe.get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | CancellationException e) {
            log.debug("Voice input probe failed: {}", e.toString());
            return false;
        }
    }

    @Override
    public VoiceSession listen(List<String> phrases, Consumer<Heard> onHeard) {
        VoiceGrammar grammar = VoiceGrammar.of(phrases);
        Session session = new Session(grammar, Objects.requireNonNull(onHeard, "onHeard"));
        if (closed) {
            return session;
        }
        wanted.set(session);
        post(wake());
        return session;
    }

    /**
     * Stops recognition, releases the engine and ends both threads. Later calls to
     * {@code listen} return sessions that hear nothing, and {@code available} is false.
     */
    @Override
    public void close() {
        Thread engine;
        ExecutorService dispatch;
        synchronized (threadLock) {
            closed = true;
            engine = engineThread;
            dispatch = callbacks;
        }
        wanted.set(null);
        if (engine != null) {
            tasks.add(wake());
            joinQuietly(engine);
        }
        if (dispatch != null) {
            dispatch.shutdown();
        }
    }

    /**
     * Queues {@code task} for the engine thread, starting the thread if there is none or the
     * last one died. False once closed.
     */
    private boolean post(FutureTask<?> task) {
        synchronized (threadLock) {
            if (closed) {
                return false;
            }
            if (engineThread == null || !engineThread.isAlive()) {
                engineThread = platformThread("voice-engine").start(this::runEngine);
            }
            tasks.add(task);
        }
        return true;
    }

    /** A no-op task whose only job is to wake the engine thread. */
    private static FutureTask<Void> wake() {
        return new FutureTask<>(() -> null);
    }

    private void runEngine() {
        Session applied = null;
        try {
            while (!closed) {
                applied = step(applied);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            cancelQueued();
            stopQuietly();
            shutdownQuietly();
        }
    }

    /**
     * One turn of the engine loop: requests, then reconcile, then one wait on the engine.
     * A fault nothing below expected drops the wanted session rather than killing the thread,
     * so a later {@code listen} still works.
     */
    private Session step(Session applied) throws InterruptedException {
        try {
            runTasks(applied == null);
            Session now = reconcile(applied);
            return now == null ? null : pump(now);
        } catch (RuntimeException e) {
            log.warn("Voice input stopped after an engine fault: {}", e.toString());
            wanted.compareAndSet(wanted.get(), null);
            stopQuietly();
            return null;
        }
    }

    /** Runs queued requests, first waiting for one when there is nothing else to do. */
    private void runTasks(boolean idle) throws InterruptedException {
        if (idle) {
            tasks.take().run();
        }
        for (FutureTask<?> task = tasks.poll(); task != null; task = tasks.poll()) {
            task.run();
        }
    }

    /** Brings the engine into line with the wanted session; returns the one now applied. */
    private Session reconcile(Session applied) {
        Session want = closed ? null : wanted.get();
        if (want == applied) {
            return applied;
        }
        if (want == null) {
            stopQuietly();
            return null;
        }
        try {
            backend.listen(want.grammar);
            log.info("Voice input listening for {} phrase(s)", want.grammar.phrases().size());
            return want;
        } catch (RuntimeException e) {
            log.warn("Voice input could not start: {}", e.getMessage());
            wanted.compareAndSet(want, null);
            stopQuietly();
            return null;
        }
    }

    /** One wait on the engine; hands what it heard to the callback thread. */
    private Session pump(Session applied) {
        List<Recognition> heard;
        try {
            heard = backend.await(POLL_INTERVAL);
        } catch (RuntimeException e) {
            log.warn("Voice input stopped: {}", e.getMessage());
            wanted.compareAndSet(applied, null);
            stopQuietly();
            return null;
        }
        for (Recognition recognition : heard) {
            applied.grammar.phraseForRuleId(recognition.ruleId()).ifPresent(phrase ->
                    deliver(applied, new Heard(phrase, recognition.confidence())));
        }
        return applied;
    }

    private void deliver(Session session, Heard heard) {
        ExecutorService dispatch;
        synchronized (threadLock) {
            if (closed) {
                return;
            }
            if (callbacks == null) {
                callbacks = Executors.newSingleThreadExecutor(platformThread("voice-callback").factory());
            }
            dispatch = callbacks;
        }
        try {
            dispatch.execute(() -> session.invoke(heard));
        } catch (RejectedExecutionException e) {
            log.debug("Voice callback dropped during shutdown: {}", heard.phrase());
        }
    }

    /** Cancels queued requests, so a caller waiting on one (a probe) returns at once. */
    private void cancelQueued() {
        synchronized (threadLock) {
            for (FutureTask<?> task = tasks.poll(); task != null; task = tasks.poll()) {
                task.cancel(false);
            }
        }
    }

    private void shutdownQuietly() {
        try {
            backend.shutdown();
        } catch (RuntimeException e) {
            log.debug("Voice input shutdown failed: {}", e.getMessage());
        }
    }

    private void stopQuietly() {
        try {
            backend.stop();
        } catch (RuntimeException e) {
            log.debug("Voice input stop failed: {}", e.getMessage());
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            if (!thread.join(CLOSE_TIMEOUT)) {
                log.warn("Voice engine thread did not stop within {}", CLOSE_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // rule-exception: {rule:prefer-virtual-threads} — see CLAUDE.md "Script runner
    // threads are platform threads". voice-engine holds a COM apartment, which is bound to an
    // OS thread; voice-callback runs untrusted script callbacks, which must not be able to pin
    // a virtual-thread carrier.
    private static Thread.Builder.OfPlatform platformThread(String name) {
        return Thread.ofPlatform().name(name).daemon(true).inheritInheritableThreadLocals(false);
    }

    /** One {@code listen} call. Hears only while it is the wanted session. */
    private final class Session implements VoiceSession {

        private final VoiceGrammar grammar;
        private final Consumer<Heard> onHeard;

        Session(VoiceGrammar grammar, Consumer<Heard> onHeard) {
            this.grammar = grammar;
            this.onHeard = onHeard;
        }

        @Override
        public void close() {
            if (wanted.compareAndSet(this, null)) {
                post(wake());
            }
        }

        /** Runs the callback, unless this session was closed or replaced since it heard. */
        void invoke(Heard heard) {
            if (wanted.get() != this) {
                return;
            }
            try {
                onHeard.accept(heard);
            } catch (RuntimeException e) {
                log.warn("Voice callback for \"{}\" threw", heard.phrase(), e);
            }
        }
    }
}
