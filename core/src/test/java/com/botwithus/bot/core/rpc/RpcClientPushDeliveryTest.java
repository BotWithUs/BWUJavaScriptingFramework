package com.botwithus.bot.core.rpc;

import com.botwithus.bot.core.msgpack.MessagePackCodec;
import com.botwithus.bot.core.pipe.PipeClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pushes reach their topic's handler over a real byte-mode named pipe, whichever
 * thread happens to read them.
 *
 * <p>Before plugin UI the host dropped every frame that was not the reply it was
 * waiting for, in two places: a caller waiting for its reply skipped any frame
 * with another id, and the idle reader drained and discarded. {@code ui.event}
 * pushes have to be delivered, so both paths are exercised here: pushes landing
 * in the middle of a call (in the same write as the reply, with the reply then
 * split across two writes) and a push landing between calls.</p>
 */
@EnabledOnOs(OS.WINDOWS)
class RpcClientPushDeliveryTest {

    private static final String TOPIC = "ui.event";
    private static final long DELIVERY_TIMEOUT_SECONDS = 10L;
    private static final long CALL_TIMEOUT_MS = 5_000L;
    /** Long enough that the client's first read returns before the second write lands. */
    private static final int PAUSE_BETWEEN_WRITES_MS = 150;
    /** Inside the reply's body, so its first write ends mid-frame. */
    private static final int REPLY_SPLIT = 6;

    private static final int SUBSCRIBE_ID = 1;
    private static final int FIRST_CALL_ID = 2;
    private static final int SECOND_CALL_ID = 3;
    // Boxed: the codec decodes every integer to a Long, and these are compared with what it decoded.
    private static final Long FIRST_HANDLE = 101L;
    private static final Long SECOND_HANDLE = 102L;
    private static final Long IDLE_HANDLE = 103L;
    private static final long OTHER_TOPIC_HANDLE = 999L;

    private static String uniquePipeName() {
        return "BotWithUs_test_push_" + ProcessHandle.current().pid() + "_" + System.nanoTime();
    }

    private static byte[] reply(int id, Map<String, Object> result) {
        return ScriptedAgentPipe.frame(MessagePackCodec.encode(Map.of("id", id, "result", result)));
    }

    private static byte[] push(String topic, long handle) {
        Map<String, Object> data = Map.of("handle", handle, "hook", "destroyed", "reason", "parent_gone");
        return ScriptedAgentPipe.frame(MessagePackCodec.encode(Map.of("topic", topic, "data", data)));
    }

    @Test
    @DisplayName("pushes mid-call and between calls are all delivered, in order")
    void pushesAreDeliveredWhicheverThreadReadsThem() throws Exception {
        byte[] firstReply = reply(FIRST_CALL_ID, Map.of("handles", List.of()));
        List<List<byte[]>> script = List.of(
                List.of(reply(SUBSCRIBE_ID, Map.of("ok", true))),
                List.of(ScriptedAgentPipe.concat(push(TOPIC, FIRST_HANDLE), push("other.topic",
                                OTHER_TOPIC_HANDLE), push(TOPIC, SECOND_HANDLE),
                                ScriptedAgentPipe.slice(firstReply, 0, REPLY_SPLIT)),
                        ScriptedAgentPipe.slice(firstReply, REPLY_SPLIT, firstReply.length)),
                List.of(reply(SECOND_CALL_ID, Map.of("handles", List.of())), push(TOPIC, IDLE_HANDLE)));
        BlockingQueue<Map<String, Object>> delivered = new LinkedBlockingQueue<>();

        try (ScriptedAgentPipe agent = ScriptedAgentPipe.start(uniquePipeName(), script,
                PAUSE_BETWEEN_WRITES_MS);
             PipeClient pipe = new PipeClient(agent.pipeName())) {
            RpcClient rpc = new RpcClient(pipe);
            rpc.setTimeout(CALL_TIMEOUT_MS);
            rpc.start();
            rpc.subscribeTopic(TOPIC, delivered::add);

            Map<String, Object> first = rpc.callSync("ui.list", Map.of());
            assertEquals(List.of(), first.get("handles"), "the split reply must decode whole");
            assertEquals(FIRST_HANDLE, nextHandle(delivered), "first push, read while the call waited");
            assertEquals(SECOND_HANDLE, nextHandle(delivered), "second push, same write");

            rpc.callSync("ui.list", Map.of());
            assertEquals(IDLE_HANDLE, nextHandle(delivered), "a push between calls, read by the reader");
            assertNull(delivered.poll(), "a push on a topic with no handler must not reach this one");
            rpc.close();
        }
    }

    /** The next delivered push's handle, as the codec decodes it (every integer is a Long). */
    private static Object nextHandle(BlockingQueue<Map<String, Object>> delivered)
            throws InterruptedException {
        Map<String, Object> data = delivered.poll(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return data == null ? null : data.get("handle");
    }
}
