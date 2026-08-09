package com.botwithus.bot.core.rpc;

import com.botwithus.bot.core.pipe.PipeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live regression net for the retired walker/pathfinder RPC surface.
 *
 * <p>The agent used to register nine walker method names that answered with
 * inert sentinel shapes — they never moved a character or computed a path.
 * They were removed, and pathfinding is the host's job (WorldWalker). This test
 * pins that removal from the consumer side: the names must be absent from the
 * agent's method catalog, and calling one must raise {@link RpcException}
 * rather than silently succeeding.</p>
 *
 * <p>Its purpose is to fail loudly if someone re-registers a sentinel. A
 * sentinel that answers {@code {}} is indistinguishable from a working walker
 * at the call site, which is exactly the failure mode the removal closed.</p>
 *
 * <p>Disabled by default — opt in with {@code -Dbotwithus.smoke.live=true}.
 * Requires the rebuilt agent injected into a running game client.</p>
 */
class LiveRetiredWalkerRpcTest {

    private static final Logger log = LoggerFactory.getLogger(LiveRetiredWalkerRpcTest.class);

    /** The exact names removed from the agent's method table. */
    private static final List<String> RETIRED_METHODS = List.of(
            "walk_to",
            "walk_world_path",
            "walk_cancel",
            "walk_status",
            "is_reachable",
            "find_path",
            "find_world_path",
            "region_cache_info",
            "region_cache_clear");

    /** Substring the dispatcher puts in the error envelope for an unknown method. */
    private static final String UNKNOWN_METHOD_ERROR = "method not found";

    /**
     * A method that is known to survive the retirement. Used as the positive
     * control on the catalog — without it, a null or empty reply satisfies
     * every absence assertion and the test reports green while proving nothing.
     */
    private static final String SURVIVING_METHOD = "queue_action";

    @Test
    @EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
    void retiredWalkerMethodsAreAbsentFromTheCatalog() {
        List<String> pipes = PipeClient.scanPipes();
        if (pipes.isEmpty()) {
            fail("No BotWithUs_<pid> pipe visible — inject the agent into a running game first");
        }

        try (PipeClient pipe = new PipeClient(pipes.get(0))) {
            RpcClient rpc = new RpcClient(pipe);

            // rpc.list_methods answers with a top-level array of name strings.
            // Rendering it is enough for a containment check and keeps this test
            // off the codec's untyped-collection seam; none of the retired names
            // is a substring of any surviving method name.
            String catalog = String.valueOf(rpc.callSyncRaw("rpc.list_methods", Map.of()));
            log.info("rpc.list_methods: {}", catalog);

            // Positive control, and it must come first. callSyncRaw yields null
            // for a reply carrying no result, and String.valueOf(null) is the
            // literal "null" — which contains none of the retired names and so
            // satisfies every assertion below. Prove the catalog is real before
            // trusting what is missing from it.
            assertTrue(catalog.contains(SURVIVING_METHOD),
                    "rpc.list_methods did not contain the known-live method "
                            + SURVIVING_METHOD + " — the catalog is unusable, so the "
                            + "absence of the retired names below proves nothing. Got: "
                            + catalog);

            for (String method : RETIRED_METHODS) {
                assertFalse(catalog.contains(method),
                        "retired method still registered in rpc.list_methods: " + method);
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
    void retiredWalkerMethodsRaiseMethodNotFound() {
        List<String> pipes = PipeClient.scanPipes();
        if (pipes.isEmpty()) {
            fail("No BotWithUs_<pid> pipe visible — inject the agent into a running game first");
        }

        try (PipeClient pipe = new PipeClient(pipes.get(0))) {
            RpcClient rpc = new RpcClient(pipe);

            for (String method : RETIRED_METHODS) {
                RpcException thrown = assertThrows(RpcException.class,
                        () -> rpc.callSync(method, Map.of()),
                        "retired method answered instead of erroring: " + method);
                log.info("{} -> {}", method, thrown.getMessage());
                assertTrue(thrown.getMessage().contains(UNKNOWN_METHOD_ERROR),
                        "expected an unknown-method error for " + method
                                + ", got: " + thrown.getMessage());
            }
        }
    }
}
