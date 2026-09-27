package com.botwithus.bot.cli;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.groups.ClientGroup;
import com.botwithus.bot.cli.groups.GroupId;
import com.botwithus.bot.cli.groups.GroupStore;
import com.botwithus.bot.cli.groups.GroupsFile;
import com.botwithus.bot.cli.log.LogBuffer;
import com.botwithus.bot.cli.log.LogCapture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The connection and group tables are mutated by the command thread, the pipe
 * scanner and reconnects while the render thread reads them every frame. These
 * tests hammer the real {@link CliContext} from several writer threads while a
 * reader iterates, and check that every read is a consistent snapshot and that no
 * write is lost.
 */
class CliContextConcurrencyTest {

    private static final int WRITERS = 4;
    private static final int CONNECTION_ROUNDS = 400;
    private static final int GROUP_ROUNDS = 60;
    private static final long JOIN_TIMEOUT_S = 30;
    private static final String SHARED_GROUP = "shared";

    @TempDir
    Path tempDir;

    private CliContext ctx;

    @BeforeEach
    void setUp() {
        PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
        LogBuffer logBuffer = new LogBuffer();
        ctx = new CliContext(logBuffer, new LogCapture(logBuffer, discard, discard),
                tempDir.resolve(GroupsFile.FILE_NAME));
    }

    @Test
    void connectionsMutatedConcurrentlyStayConsistentAndLoseNothing() throws InterruptedException {
        Connection[][] conns = new Connection[WRITERS][CONNECTION_ROUNDS];
        for (int w = 0; w < WRITERS; w++) {
            for (int n = 0; n < CONNECTION_ROUNDS; n++) {
                conns[w][n] = mockConnection(connName(w, n));
            }
        }
        Hammer hammer = new Hammer(w -> writeConnections(conns[w]), this::readConnectionsOnce);
        hammer.run();

        List<String> names = ctx.getConnections().stream().map(Connection::getName).toList();
        assertEquals(WRITERS * CONNECTION_ROUNDS / 2, names.size(), "a write was lost: " + names.size());
        for (int w = 0; w < WRITERS; w++) {
            assertKeptInInsertionOrder(names, w);
        }
        assertNotNull(ctx.getActiveConnection(), "the active connection must still resolve");
    }

    @Test
    void groupsMutatedConcurrentlyStayConsistentAndLoseNothing() throws InterruptedException {
        GroupId shared = ctx.getGroupStore().create(SHARED_GROUP, Optional.empty()).orElseThrow().id();
        Hammer hammer = new Hammer(writer -> writeGroups(writer, shared), this::readGroupsOnce);
        hammer.run();

        List<ClientGroup> groups = ctx.getGroupStore().all();
        assertEquals(1 + WRITERS * GROUP_ROUNDS / 2, groups.size(), "a group write was lost");
        assertEquals(WRITERS * GROUP_ROUNDS, ctx.getGroupStore().get(shared).orElseThrow().members().size(),
                "a member write was lost");
        GroupStore saved = new GroupStore(new GroupsFile(tempDir.resolve(GroupsFile.FILE_NAME)));
        saved.load(pipe -> Optional.empty());
        assertEquals(groups, saved.all(), "the file must end up holding the latest state");
    }

    @Test
    void connectionsKeepInsertionOrderAndReassignActiveToTheFirst() {
        for (String name : List.of("a", "b", "c")) {
            assertTrue(ctx.registerConnection(mockConnection(name)));
        }
        ctx.disconnect("b", true);
        ctx.registerConnection(mockConnection("d"));
        assertEquals(List.of("a", "c", "d"), ctx.getConnections().stream().map(Connection::getName).toList());
        assertEquals("d", ctx.getActiveConnectionName());

        ctx.disconnect("d", true);
        assertEquals("a", ctx.getActiveConnectionName());
        ctx.handleConnectionError("a");
        ctx.handleConnectionError("c");
        assertNull(ctx.getActiveConnectionName());
        assertFalse(ctx.hasConnections());
    }

    @Test
    void registeringANameTwiceKeepsTheFirstConnection() {
        Connection first = mockConnection("dup");
        assertTrue(ctx.registerConnection(first));
        assertFalse(ctx.registerConnection(mockConnection("dup")));
        assertEquals(1, ctx.getConnections().size());
        assertSame(first, ctx.getActiveConnection());
    }

    @Test
    void connectionListIsASnapshotNotALiveView() {
        ctx.registerConnection(mockConnection("a"));
        var before = ctx.getConnections();
        ctx.registerConnection(mockConnection("b"));
        assertEquals(1, before.size(), "a list handed out earlier must not change under its reader");
        assertEquals(2, ctx.getConnections().size());
    }

    // ── Writers and readers ────────────────────────────────────────────────

    /** Registers every connection, then removes every odd one through the two removal paths. */
    private void writeConnections(Connection[] mine) {
        for (int n = 0; n < mine.length; n++) {
            ctx.registerConnection(mine[n]);
            if (n % 2 == 1) {
                removeConnection(mine[n].getName(), n / 2 % 2 == 0);
            }
        }
    }

    private void removeConnection(String name, boolean viaDisconnect) {
        if (viaDisconnect) {
            ctx.disconnect(name, true);
        } else {
            ctx.handleConnectionError(name);
        }
    }

    /** Creates groups, deleting every odd one, and adds one account per round to the shared group. */
    private void writeGroups(int writer, GroupId shared) {
        for (int n = 0; n < GROUP_ROUNDS; n++) {
            String name = connName(writer, n);
            GroupId created = ctx.getGroupStore().create(name, Optional.empty()).orElseThrow().id();
            ctx.getGroupStore().addMember(shared, ClientKey.account(name));
            if (n % 2 == 1) {
                ctx.getGroupStore().delete(created);
            }
        }
    }

    /** One render-thread-style pass: iterate every connection and check the pass is self-consistent. */
    private boolean readConnectionsOnce() {
        Set<String> seen = new HashSet<>();
        for (Connection conn : ctx.getConnections()) {
            assertNotNull(conn, "null in a connection snapshot");
            assertTrue(seen.add(conn.getName()), "duplicate in a connection snapshot: " + conn.getName());
        }
        ctx.getActiveConnection();
        return !seen.isEmpty();
    }

    private boolean readGroupsOnce() {
        int members = 0;
        for (ClientGroup group : ctx.getGroupStore().all()) {
            assertNotNull(group, "null in a group snapshot");
            for (String member : group.members()) {
                assertNotNull(member, "null member in a group");
                members++;
            }
        }
        return members > 0;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String connName(int writer, int round) {
        return "w" + writer + "-" + round;
    }

    private static Connection mockConnection(String name) {
        Connection conn = mock(Connection.class);
        when(conn.getName()).thenReturn(name);
        // The client registry reads these from every registered connection.
        when(conn.getGameStatus()).thenReturn(GameStatus.UNKNOWN);
        return conn;
    }

    /** Writer {@code w} kept its even rounds; they must appear in the order it added them. */
    private static void assertKeptInInsertionOrder(List<String> names, int writer) {
        List<String> expected = new ArrayList<>();
        for (int n = 0; n < CONNECTION_ROUNDS; n += 2) {
            expected.add(connName(writer, n));
        }
        String prefix = "w" + writer + "-";
        assertEquals(expected, names.stream().filter(s -> s.startsWith(prefix)).toList());
    }

    /**
     * Runs {@link #WRITERS} writer threads to completion while one reader loops
     * over {@code read}. Fails on any throwable from any thread, on a hang, and if
     * the reader never observed a non-empty table while the writers ran (which
     * would mean the test raced nothing).
     */
    private record Hammer(IntConsumer write, ReadPass read) {

        void run() throws InterruptedException {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean writing = new AtomicBoolean(true);
            AtomicInteger busyReads = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            Thread reader = start(failure, start, () -> {
                while (writing.get()) {
                    if (read.once()) {
                        busyReads.incrementAndGet();
                    }
                }
            });
            List<Thread> writers = new ArrayList<>();
            for (int w = 0; w < WRITERS; w++) {
                int id = w;
                writers.add(start(failure, start, () -> write.accept(id)));
            }
            start.countDown();
            joinAll(writers);
            writing.set(false);
            joinAll(List.of(reader));
            if (failure.get() != null) {
                fail("concurrent access failed: " + failure.get(), failure.get());
            }
            assertTrue(busyReads.get() > 0, "the reader never overlapped a non-empty table");
        }

        private static Thread start(AtomicReference<Throwable> failure, CountDownLatch gate, Runnable body) {
            return Thread.ofPlatform().daemon().start(() -> {
                try {
                    gate.await();
                    body.run();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }

        private static void joinAll(List<Thread> threads) throws InterruptedException {
            for (Thread t : threads) {
                if (!t.join(Duration.ofSeconds(JOIN_TIMEOUT_S))) {
                    fail("thread " + t.getName() + " hung: the table is likely corrupted");
                }
            }
        }
    }

    @FunctionalInterface
    private interface ReadPass {
        /** @return whether this pass saw a non-empty table */
        boolean once();
    }
}
