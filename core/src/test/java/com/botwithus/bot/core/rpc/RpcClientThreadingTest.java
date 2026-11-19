package com.botwithus.bot.core.rpc;

import com.botwithus.bot.core.msgpack.MessagePackCodec;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.runtime.ScriptGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which thread, carrying what, runs the RPC client's callbacks, over real byte-mode
 * named pipes.
 *
 * <ul>
 *   <li>The push thread is created lazily, by whichever thread routes the first
 *       push, often a script thread waiting on its reply. It must not inherit that
 *       script's tag, or every later push would run as that script. (Review probe
 *       {@code ProbePushThreadTagTest}, kept as a regression test.)</li>
 *   <li>A connection-reset listener runs before any call can reach the new pipe:
 *       a call slipping in between the swap and the listener would create something
 *       the listener then forgets.</li>
 * </ul>
 */
@EnabledOnOs(OS.WINDOWS)
class RpcClientThreadingTest {

    private static final long AWAIT_SECONDS = 10L;
    private static final long CALL_TIMEOUT_MS = 5_000L;
    private static final int PAUSE_MS = 150;
    /** How long the reset listener holds the reconnect while a call tries to get through. */
    private static final long LISTENER_HOLD_MS = 400L;
    private static final String SCRIPT = "Alpha";

    private static String uniquePipeName(String what) {
        return "BotWithUs_test_" + what + "_" + ProcessHandle.current().pid() + "_" + System.nanoTime();
    }

    private static byte[] reply(int id, Map<String, Object> result) {
        return ScriptedAgentPipe.frame(MessagePackCodec.encode(Map.of("id", id, "result", result)));
    }

    private static byte[] push(long handle) {
        return ScriptedAgentPipe.frame(MessagePackCodec.encode(Map.of("topic", "ui.event",
                "data", Map.of("handle", handle, "hook", "destroyed"))));
    }

    @Test
    @DisplayName("the push thread does not inherit the tag of the script whose call read the first push")
    void pushThread_inheritsNoScriptTag() throws Exception {
        List<List<byte[]>> script = List.of(
                List.of(reply(1, Map.of("ok", true))),
                List.of(ScriptedAgentPipe.concat(push(5L), reply(2, Map.of("handles", List.of())))),
                List.of(reply(3, Map.of("handles", List.of())), push(6L)));
        ScriptGate gate = new ScriptGate();
        BlockingQueue<String> tags = new LinkedBlockingQueue<>();
        try (ScriptedAgentPipe agent = ScriptedAgentPipe.start(uniquePipeName("pushtag"), script, PAUSE_MS);
             PipeClient pipe = new PipeClient(agent.pipeName())) {
            RpcClient rpc = new RpcClient(pipe);
            rpc.setTimeout(CALL_TIMEOUT_MS);
            rpc.start();
            rpc.subscribeTopic("ui.event", d -> tags.add(String.valueOf(gate.current())));
            Thread scriptThread = Thread.ofPlatform().start(() -> {
                gate.enter(SCRIPT);
                rpc.callSync("ui.list", Map.of());
            });
            scriptThread.join();
            assertEquals("null", tags.poll(AWAIT_SECONDS, TimeUnit.SECONDS), "push read mid-call by the script");
            rpc.callSync("ui.list", Map.of());
            assertEquals("null", tags.poll(AWAIT_SECONDS, TimeUnit.SECONDS), "a later push, read by the reader");
            rpc.close();
        }
    }

    @Test
    @DisplayName("no call reaches the new pipe until the connection-reset listeners have run")
    void resetListener_runsBeforeAnyCallReachesTheNewPipe() throws Exception {
        try (FakeAgentPipe first = FakeAgentPipe.start(uniquePipeName("reset_a"));
             FakeAgentPipe second = FakeAgentPipe.start(uniquePipeName("reset_b"));
             PipeClient pipe = new PipeClient(first.pipeName())) {
            RpcClient rpc = new RpcClient(pipe);
            rpc.setTimeout(CALL_TIMEOUT_MS);
            rpc.start();
            CountDownLatch callDone = new CountDownLatch(1);
            AtomicBoolean isCallDoneDuringListener = new AtomicBoolean(true);
            rpc.addConnectionResetListener(() -> {
                Thread.ofPlatform().start(() -> {
                    rpc.callSync("ping", Map.of());
                    callDone.countDown();
                });
                isCallDoneDuringListener.set(awaitQuietly(callDone, LISTENER_HOLD_MS));
            });

            rpc.reconnect(second.pipeName());

            assertFalse(isCallDoneDuringListener.get(),
                    "a call completed on the new pipe while the reset listener was still running");
            assertTrue(callDone.await(AWAIT_SECONDS, TimeUnit.SECONDS), "positive control: the call goes through after");
            rpc.close();
        }
    }

    @Test
    @DisplayName("a subscription that cannot be re-sent after a reconnect is reported lost, and its handler dropped")
    void failedResubscribe_reportsTheSubscriptionLost() throws Exception {
        List<List<byte[]>> script = List.of(List.of(reply(1, Map.of("ok", true))));
        // The echo agent answers the re-subscribe with the request itself, which carries
        // no ok: true, so the re-subscribe is refused.
        try (ScriptedAgentPipe first = ScriptedAgentPipe.start(uniquePipeName("lost_a"), script, PAUSE_MS);
             FakeAgentPipe second = FakeAgentPipe.start(uniquePipeName("lost_b"));
             PipeClient pipe = new PipeClient(first.pipeName())) {
            RpcClient rpc = new RpcClient(pipe);
            rpc.setTimeout(CALL_TIMEOUT_MS);
            rpc.start();
            CountDownLatch lost = new CountDownLatch(1);
            rpc.subscribeTopic("ui.event", d -> { }, lost::countDown);

            rpc.reconnect(second.pipeName());

            assertTrue(lost.await(AWAIT_SECONDS, TimeUnit.SECONDS), "the subscriber was never told");
            rpc.close();
        }
    }

    private static boolean awaitQuietly(CountDownLatch latch, long ms) {
        try {
            return latch.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
