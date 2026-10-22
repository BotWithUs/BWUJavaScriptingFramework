package com.botwithus.bot.core.voice;

import com.botwithus.bot.api.voice.Heard;
import com.botwithus.bot.api.voice.VoiceSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lifecycle and threading of {@link VoiceInputService}, over a scripted backend. */
class VoiceInputServiceTest {

    private static final long WAIT_MS = 5_000;
    /** Long enough for a wrongly delivered callback to have arrived. */
    private static final long QUIET_MS = 300;

    private final FakeBackend backend = new FakeBackend();
    private final VoiceInputService service = new VoiceInputService(backend);
    private final BlockingQueue<Heard> heard = new LinkedBlockingQueue<>();
    private final BlockingQueue<Thread> callbackThreads = new LinkedBlockingQueue<>();

    @AfterEach
    void closeService() {
        service.close();
    }

    @Test
    void listen_recognisedRule_deliversTheCallersPhraseOnTheCallbackThread() throws InterruptedException {
        String hello = new String("hello bot");
        service.listen(List.of(hello, "stop"), this::record);
        awaitTrue(() -> backend.active != null);

        backend.recognise(VoiceGrammar.ruleIdOf(0), 0.9f);
        Heard first = heard.poll(WAIT_MS, TimeUnit.MILLISECONDS);

        assertNotNull(first);
        assertSame(hello, first.phrase());
        assertEquals(0.9f, first.confidence());
        Thread thread = callbackThreads.poll();
        assertNotNull(thread);
        assertEquals("voice-callback", thread.getName());
        assertFalse(thread.isVirtual());
    }

    @Test
    void listen_backendRunsOnOnePlatformEngineThread() {
        service.listen(List.of("stop"), this::record);
        awaitTrue(() -> backend.active != null);

        assertEquals("voice-engine", backend.engineThread.getName());
        assertFalse(backend.engineThread.isVirtual());
    }

    @Test
    void listen_again_replacesTheGrammarAndSilencesTheOldSession() throws InterruptedException {
        List<Heard> oldHeard = new CopyOnWriteArrayList<>();
        service.listen(List.of("old"), oldHeard::add);
        awaitTrue(() -> backend.active != null);
        service.listen(List.of("new one", "new two"), this::record);
        awaitTrue(() -> backend.active.phrases().size() == 2);

        backend.recognise(VoiceGrammar.ruleIdOf(1), 1f);

        assertEquals("new two", heard.poll(WAIT_MS, TimeUnit.MILLISECONDS).phrase());
        assertTrue(oldHeard.isEmpty());
    }

    @Test
    void sessionClose_stopsTheBackendAndDropsWhatItHeardAfterwards() throws InterruptedException {
        VoiceSession session = service.listen(List.of("stop"), this::record);
        awaitTrue(() -> backend.active != null);

        session.close();
        awaitTrue(() -> backend.active == null);
        backend.recognise(VoiceGrammar.ruleIdOf(0), 1f);

        assertNull(heard.poll(QUIET_MS, TimeUnit.MILLISECONDS));
        assertTrue(backend.stops > 0);
    }

    @Test
    void sessionClose_dropsCallbacksAlreadyQueuedBehindASlowOne() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        VoiceSession session = service.listen(List.of("stop"), h -> {
            if (calls.incrementAndGet() == 1) {
                awaitQuietly(release);
            }
        });
        awaitTrue(() -> backend.active != null);
        backend.recognise(VoiceGrammar.ruleIdOf(0), 1f);
        awaitTrue(() -> calls.get() == 1);
        backend.recognise(VoiceGrammar.ruleIdOf(0), 1f);
        awaitTrue(backend.pending::isEmpty);
        Thread.sleep(QUIET_MS);

        session.close();
        release.countDown();
        Thread.sleep(QUIET_MS);

        assertEquals(1, calls.get());
    }

    @Test
    void closingAReplacedSession_leavesTheNewOneListening() throws InterruptedException {
        VoiceSession old = service.listen(List.of("old"), h -> { });
        service.listen(List.of("new"), this::record);
        awaitTrue(() -> backend.active != null && backend.active.phrases().equals(List.of("new")));

        old.close();
        backend.recognise(VoiceGrammar.ruleIdOf(0), 1f);

        assertEquals("new", heard.poll(WAIT_MS, TimeUnit.MILLISECONDS).phrase());
    }

    @Test
    void backendRefusingToStart_leavesASilentSessionAndALaterListenStillWorks() throws InterruptedException {
        backend.failNextListen = true;
        service.listen(List.of("broken"), this::record);
        awaitTrue(() -> backend.listenAttempts == 1);

        service.listen(List.of("working"), this::record);
        awaitTrue(() -> backend.active != null);
        backend.recognise(VoiceGrammar.ruleIdOf(0), 1f);

        assertEquals("working", heard.poll(WAIT_MS, TimeUnit.MILLISECONDS).phrase());
    }

    @Test
    void engineThreadKilledByAnError_isReplacedOnTheNextListen() throws InterruptedException {
        backend.errorOnNextListen = true;
        service.listen(List.of("fatal"), this::record);
        awaitTrue(() -> backend.shutDown);
        Thread dead = backend.engineThread;
        awaitTrue(() -> !dead.isAlive());

        service.listen(List.of("again"), this::record);
        awaitTrue(() -> backend.active != null);
        backend.recognise(VoiceGrammar.ruleIdOf(0), 1f);

        assertEquals("again", heard.poll(WAIT_MS, TimeUnit.MILLISECONDS).phrase());
        assertFalse(backend.engineThread == dead);
    }

    @Test
    void throwingCallback_doesNotStopLaterDeliveries() throws InterruptedException {
        service.listen(List.of("boom", "fine"), h -> {
            if (h.phrase().equals("boom")) {
                throw new IllegalStateException("script bug");
            }
            heard.add(h);
        });
        awaitTrue(() -> backend.active != null);

        backend.recognise(VoiceGrammar.ruleIdOf(0), 1f);
        backend.recognise(VoiceGrammar.ruleIdOf(1), 1f);

        assertEquals("fine", heard.poll(WAIT_MS, TimeUnit.MILLISECONDS).phrase());
    }

    @Test
    void unknownRuleId_isIgnored() throws InterruptedException {
        service.listen(List.of("stop"), this::record);
        awaitTrue(() -> backend.active != null);

        backend.recognise(VoiceGrammar.ruleIdOf(7), 1f);

        assertNull(heard.poll(QUIET_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    void available_reportsTheProbe_andFalseWhenItThrows() {
        backend.probeResult = true;
        assertTrue(service.available());

        backend.probeResult = false;
        assertFalse(service.available());

        backend.probeThrows = true;
        assertFalse(service.available());
    }

    @Test
    void available_beforeAnyListen_startsNoRecognition() {
        service.available();

        assertEquals(0, backend.listenAttempts);
    }

    @Test
    void close_stopsAndShutsDownTheBackend_andLaterCallsAreInert() {
        service.listen(List.of("stop"), this::record);
        awaitTrue(() -> backend.active != null);

        service.close();

        assertTrue(backend.shutDown);
        assertFalse(service.available());
        VoiceSession late = service.listen(List.of("late"), this::record);
        late.close();
        assertEquals(1, backend.listenAttempts);
    }

    @Test
    void close_neverUsed_touchesNothing() {
        service.close();

        assertFalse(backend.shutDown);
        assertNull(backend.engineThread);
    }

    @Test
    void listen_badArguments_areRejectedOnTheCallersThread() {
        assertThrows(IllegalArgumentException.class, () -> service.listen(List.of(), this::record));
        assertThrows(NullPointerException.class, () -> service.listen(List.of("stop"), null));
        assertNull(backend.engineThread);
    }

    private void record(Heard h) {
        callbackThreads.add(Thread.currentThread());
        heard.add(h);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + WAIT_MS + " ms");
            }
            Thread.onSpinWait();
        }
    }

    /** A backend whose recognitions the test injects. */
    private static final class FakeBackend implements RecognizerBackend {

        final BlockingQueue<Recognition> pending = new LinkedBlockingQueue<>();
        volatile VoiceGrammar active;
        volatile Thread engineThread;
        volatile int listenAttempts;
        volatile int stops;
        volatile boolean shutDown;
        volatile boolean failNextListen;
        volatile boolean errorOnNextListen;
        volatile boolean probeResult;
        volatile boolean probeThrows;

        void recognise(long ruleId, float confidence) {
            pending.add(new Recognition(ruleId, confidence));
        }

        @Override
        public boolean probe() {
            engineThread = Thread.currentThread();
            if (probeThrows) {
                throw new IllegalStateException("no COM here");
            }
            return probeResult;
        }

        @Override
        public void listen(VoiceGrammar grammar) {
            engineThread = Thread.currentThread();
            listenAttempts++;
            if (errorOnNextListen) {
                errorOnNextListen = false;
                throw new AssertionError("simulated fatal error on the engine thread");
            }
            if (failNextListen) {
                failNextListen = false;
                throw new SapiException("no microphone");
            }
            pending.clear();
            active = grammar;
        }

        @Override
        public List<Recognition> await(Duration timeout) {
            try {
                Recognition next = pending.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return next == null ? List.of() : List.of(next);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        }

        @Override
        public void stop() {
            stops++;
            active = null;
        }

        @Override
        public void shutdown() {
            shutDown = true;
            active = null;
        }
    }
}
