package com.botwithus.bot.core.rpc;

import com.botwithus.bot.core.impl.MapHelper;
import com.botwithus.bot.core.pipe.PipeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live smoke test for the RPC handlers added in the stale-RPC fix.
 *
 * <p>Opens the first available {@code BotWithUs_<pid>} pipe and exercises
 * the five handlers that were previously stale on the C++ side:
 * {@code get_account_info}, {@code get_current_world},
 * {@code take_screenshot}, {@code start_stream}, {@code stop_stream}.</p>
 *
 * <p>Disabled by default — opt in with {@code -Dbotwithus.smoke.live=true}.
 * Requires the rebuilt NXTLibrary DLL injected into a running game client.</p>
 */
class LiveStaleRpcSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveStaleRpcSmokeTest.class);

    private static final int GAME_STATE_IN_GAME = 30;
    private static final String NOT_IMPLEMENTED = "not_implemented";

    @Test
    @EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
    void exercisesNewlyRegisteredHandlers() {
        List<String> pipes = PipeClient.scanPipes();
        if (pipes.isEmpty()) {
            fail("No BotWithUs_<pid> pipe visible — inject the DLL into a running game first");
        }

        try (PipeClient pipe = new PipeClient(pipes.get(0))) {
            RpcClient rpc = new RpcClient(pipe);

            Map<String, Object> methods = rpc.callSync("rpc.list_methods", Map.of());
            log.info("rpc.list_methods returned {} keys (sanity check)", methods.size());

            Map<String, Object> ai = rpc.callSync("get_account_info", Map.of());
            log.info("get_account_info: {}", ai);
            assertNotNull(ai.get("display_name"), "display_name key must be present");
            assertNotNull(ai.get("jx_display_name"), "jx_display_name key must be present");
            assertInstanceOf(Boolean.class, ai.get("logged_in"), "logged_in must be a Boolean");
            assertInstanceOf(Boolean.class, ai.get("is_member"), "is_member must be a Boolean");

            Map<String, Object> cw = rpc.callSync("get_current_world", Map.of());
            log.info("get_current_world: {}", cw);
            assertNotNull(cw.get("world_id"), "world_id key must be present");
            int worldId = MapHelper.getInt(cw, "world_id");
            boolean loggedIn = MapHelper.getBool(ai, "logged_in");
            if (loggedIn) {
                assertTrue(worldId > 0, "logged_in but world_id=" + worldId);
            }

            Map<String, Object> ss = rpc.callSync("take_screenshot", Map.of());
            log.info("take_screenshot: {}", ss);
            assertEquals(NOT_IMPLEMENTED, ss.get("error"),
                    "stub must respond with error=not_implemented");

            Map<String, Object> startResp = rpc.callSync("start_stream", Map.of());
            log.info("start_stream: {}", startResp);
            assertEquals(NOT_IMPLEMENTED, startResp.get("error"),
                    "stub must respond with error=not_implemented");

            Map<String, Object> stopResp = rpc.callSync("stop_stream", Map.of());
            log.info("stop_stream: {}", stopResp);
            assertTrue(stopResp.isEmpty(),
                    "stop_stream should return an empty success map; got " + stopResp);

            // Sanity-tie back to game state — if logged_in, the LocalPlayer
            // snapshot block should agree with what get_account_info reported.
            Map<String, Object> ls = rpc.callSync("get_login_state", Map.of());
            int gameState = MapHelper.getInt(ls, "state");
            if (gameState == GAME_STATE_IN_GAME) {
                assertEquals(Boolean.TRUE, ai.get("logged_in"),
                        "get_login_state reports in-game but get_account_info says not logged in");
                assertTrue(!MapHelper.getString(ai, "display_name").isEmpty(),
                        "in-game -> non-empty display_name");
            }
        }
    }
}
