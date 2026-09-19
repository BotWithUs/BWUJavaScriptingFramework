package com.botwithus.bot.core.rpc;

import com.botwithus.bot.core.pipe.PipeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cover for the disconnect that the host could not see.
 *
 * <p>Observed in production: a script ran healthily for ~40s, then a
 * {@code get_varp} failed with {@code PipeException: Failed to send message}
 * caused by {@code IOException: The pipe is being closed} — Win32
 * {@code ERROR_NO_DATA} (232), raised when the peer has already closed its
 * handle. The disconnect handler never fired, so {@code ReconnectController}
 * never ran and the connection stayed wedged while still reporting healthy.</p>
 *
 * <p>Why the reader thread cannot cover this: its only liveness probe is
 * {@link PipeClient#available()}, and on a broken Windows pipe that returns 0
 * rather than failing. The JDK's {@code handleNonSeekAvailable}
 * ({@code src/java.base/windows/native/libjava/io_util_md.c}) swallows every
 * {@code PeekNamedPipe} error and reports zero bytes, so no
 * {@code IOException} is ever raised for {@code available()} to propagate.
 * Detection has to come from an operation that does report failure — a write,
 * or a read returning EOF.</p>
 */
@EnabledOnOs(OS.WINDOWS)
class PipeDisconnectNotificationTest {

    /** Generous upper bound on how long the handler may take to fire. */
    private static final long NOTIFY_TIMEOUT_SECONDS = 10L;

    /** Per-call deadline; short so a hang fails the test rather than stalling it. */
    private static final long CALL_TIMEOUT_MS = 3_000L;

    private static String uniquePipeName() {
        return "BotWithUs_test_" + ProcessHandle.current().pid()
                + "_" + System.nanoTime();
    }

    /**
     * Positive control. If the echo server could not serve a healthy call, the
     * disconnect assertions below would pass for the wrong reason.
     */
    @Test
    void healthyCallSucceedsAgainstTheFakeAgent() throws Exception {
        try (FakeAgentPipe agent = FakeAgentPipe.start(uniquePipeName());
             PipeClient pipe = new PipeClient(agent.pipeName())) {
            RpcClient rpc = newStartedClient(pipe);

            Map<String, Object> reply = rpc.callSync("get_varp", Map.of("id", 1));

            assertNotNull(reply, "echo server should answer a healthy call");
            assertTrue(pipe.isOpen(), "pipe should still be open after a healthy call");
        }
    }

    /**
     * The production failure. After the agent hangs up, the next call fails at
     * the write — and that failure must reach the disconnect handler, because
     * it is the only thing that starts reconnection.
     */
    @Test
    void writeFailureAfterHangUpNotifiesDisconnectHandler() throws Exception {
        try (FakeAgentPipe agent = FakeAgentPipe.start(uniquePipeName());
             PipeClient pipe = new PipeClient(agent.pipeName())) {
            RpcClient rpc = newStartedClient(pipe);
            CountDownLatch notified = new CountDownLatch(1);
            AtomicReference<Throwable> cause = new AtomicReference<>();
            rpc.setDisconnectHandler(t -> {
                cause.set(t);
                notified.countDown();
            });

            // Prove the connection is live before breaking it.
            assertNotNull(rpc.callSync("get_varp", Map.of("id", 1)));

            agent.hangUp();

            assertThrows(RpcException.class,
                    () -> rpc.callSync("get_varp", Map.of("id", 2)),
                    "call on a hung-up pipe must fail");

            assertTrue(notified.await(NOTIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "disconnect handler must fire when the transport breaks — "
                            + "without it ReconnectController never runs");
            assertNotNull(cause.get(), "handler should receive the originating cause");
        }
    }

    /**
     * Once the transport is known dead the connection must stop claiming to be
     * healthy. {@code Connection.isAlive()} is {@code pipe.isOpen()}, and the
     * status bar, diagnostics panel and auto-start manager all read it.
     */
    @Test
    void connectionStopsReportingOpenAfterTransportFailure() throws Exception {
        try (FakeAgentPipe agent = FakeAgentPipe.start(uniquePipeName());
             PipeClient pipe = new PipeClient(agent.pipeName())) {
            RpcClient rpc = newStartedClient(pipe);
            assertNotNull(rpc.callSync("get_varp", Map.of("id", 1)));

            agent.hangUp();
            assertThrows(RpcException.class, () -> rpc.callSync("get_varp", Map.of("id", 2)));

            assertFalse(pipe.isOpen(),
                    "a pipe whose peer is gone must not report open — "
                            + "Connection.isAlive() is built on this");
        }
    }

    /**
     * The handler must fire once per break, not once per failed call. A script
     * ticking against a dead pipe would otherwise launch a recovery attempt
     * every tick.
     */
    @Test
    void repeatedFailedCallsNotifyOnlyOnce() throws Exception {
        try (FakeAgentPipe agent = FakeAgentPipe.start(uniquePipeName());
             PipeClient pipe = new PipeClient(agent.pipeName())) {
            RpcClient rpc = newStartedClient(pipe);
            CountDownLatch notified = new CountDownLatch(1);
            AtomicReference<Integer> count = new AtomicReference<>(0);
            rpc.setDisconnectHandler(t -> {
                count.updateAndGet(n -> n + 1);
                notified.countDown();
            });

            assertNotNull(rpc.callSync("get_varp", Map.of("id", 1)));
            agent.hangUp();

            for (int i = 0; i < 5; i++) {
                assertThrows(RpcException.class,
                        () -> rpc.callSync("get_varp", Map.of("id", 9)));
            }

            assertTrue(notified.await(NOTIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1, count.get(),
                    "five failed calls must raise exactly one disconnect");
        }
    }

    private static RpcClient newStartedClient(PipeClient pipe) {
        RpcClient rpc = new RpcClient(pipe);
        rpc.setTimeout(CALL_TIMEOUT_MS);
        rpc.start();
        return rpc;
    }
}
