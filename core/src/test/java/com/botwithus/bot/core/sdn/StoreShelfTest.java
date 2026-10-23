package com.botwithus.bot.core.sdn;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.api.ScriptContext;
import com.botwithus.bot.core.runtime.SdnDelivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreShelfTest {

    private static final String ID = "42";
    private static final Duration SHORT_BUDGET = Duration.ofMillis(200);
    private static final long TEST_TIMEOUT_SECONDS = 10;

    public static final class Shelved implements BotScript {
        @Override public void onStart(ScriptContext context) { }
        @Override public int onLoop() { return 0; }
        @Override public void onStop() { }
    }

    public static final class Broken implements BotScript {
        @Override public void onStart(ScriptContext context) { }
        @Override public int onLoop() { return 0; }
        @Override public void onStop() { }
    }

    private static SdnDelivery.Jar jar(BotScript first, Supplier<BotScript> copies) {
        return new SdnDelivery.Jar(0, List.of(first), List.of(copies));
    }

    /** Fails if the shelf hands back the delivered object: two clients would share its per-run state. */
    @Test
    void freshCopies_areNewInstancesEachTime() {
        StoreShelf shelf = new StoreShelf();
        BotScript delivered = new Shelved();
        shelf.stock(Optional.of(ID), jar(delivered, Shelved::new));

        BotScript first = shelf.freshCopies().getFirst();
        BotScript second = shelf.freshCopies().getFirst();

        assertInstanceOf(Shelved.class, first);
        assertNotSame(delivered, first);
        assertNotSame(first, second);
        assertEquals(Set.of(ID), shelf.catalogueIds());
    }

    @Test
    void aCopyWhoseConstructorThrows_isSkippedAndTheRestStillCome() {
        StoreShelf shelf = new StoreShelf();
        shelf.stock(Optional.of(ID), jar(new Shelved(), Shelved::new));
        shelf.stock(Optional.of("43"), jar(new Broken(), () -> {
            throw new IllegalStateException("constructor threw");
        }));

        List<BotScript> copies = shelf.freshCopies();

        assertEquals(1, copies.size());
        assertInstanceOf(Shelved.class, copies.getFirst());
    }

    /**
     * S6: a copy whose constructor blocks is skipped after the budget, so the connect
     * or reload asking for copies is not held. Fails if copies are made inline.
     */
    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    void aCopyWhoseConstructorBlocks_isSkippedAfterTheBudget_andTheRestStillCome() {
        StoreShelf shelf = new StoreShelf(SHORT_BUDGET);
        CountDownLatch release = new CountDownLatch(1);
        shelf.stock(Optional.of(ID), jar(new Shelved(), Shelved::new));
        shelf.stock(Optional.of("43"), jar(new Broken(), () -> {
            awaitQuietly(release);
            return new Broken();
        }));
        try {
            List<BotScript> copies = shelf.freshCopies();

            assertEquals(1, copies.size());
            assertInstanceOf(Shelved.class, copies.getFirst());
        } finally {
            release.countDown();
        }
    }

    /**
     * B1: a copy that overran its budget is remembered, so a second call costs no
     * second budget and starts no second thread. Fails if each call retries it.
     */
    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    void twoCallsOnABlockingCopy_costOneBudgetAndOneThread() {
        StoreShelf shelf = new StoreShelf(SHORT_BUDGET);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        shelf.stock(Optional.of(ID), jar(new Broken(), () -> {
            attempts.incrementAndGet();
            awaitQuietly(release);
            return new Broken();
        }));
        try {
            shelf.freshCopies();
            Instant second = Instant.now();
            List<BotScript> again = shelf.freshCopies();

            assertTrue(again.isEmpty());
            assertTrue(Duration.between(second, Instant.now()).compareTo(SHORT_BUDGET) < 0,
                    "the second call does not wait out another budget");
            assertEquals(1, attempts.get());
            assertEquals(1, Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().equals("sdn-construct-" + Broken.class.getName())).count());
        } finally {
            release.countDown();
        }
    }

    /** L2: an interrupted caller starts no copy at all, and keeps its interrupt. */
    @Test
    void anInterruptedCaller_startsNoCopy() {
        StoreShelf shelf = new StoreShelf(SHORT_BUDGET);
        AtomicInteger attempts = new AtomicInteger();
        shelf.stock(Optional.of(ID), jar(new Shelved(), () -> {
            attempts.incrementAndGet();
            return new Shelved();
        }));
        Thread.currentThread().interrupt();
        try {
            assertTrue(shelf.freshCopies().isEmpty());
            assertEquals(0, attempts.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void anUnpairedDelivery_isShelvedUnderNoCatalogueId() {
        StoreShelf shelf = new StoreShelf();
        shelf.stock(Optional.empty(), jar(new Shelved(), Shelved::new));

        assertTrue(shelf.catalogueIds().isEmpty());
        assertEquals(Set.of(Shelved.class.getName()), shelf.classes());
    }
}
