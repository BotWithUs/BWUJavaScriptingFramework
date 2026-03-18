package com.botwithus.bot.core.blueprint.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.botwithus.bot.api.blueprint.NodeDefinition;
import com.botwithus.bot.api.blueprint.PinDefinition;
import com.botwithus.bot.api.blueprint.PinDirection;
import com.botwithus.bot.api.blueprint.PinType;

import java.util.List;
import java.util.Map;

/**
 * Registers debug/utility nodes for blueprint development.
 */
public final class DebugNodes {

    private static final Logger log = LoggerFactory.getLogger(DebugNodes.class);

    private DebugNodes() {}

    /**
     * Registers all debug nodes into the given registry.
     *
     * @param registry the node registry
     */
    public static void registerAll(NodeRegistry registry) {
        registerPrint(registry);
    }

    private static void registerPrint(NodeRegistry registry) {
        registry.register(
                new NodeDefinition("debug.print", "Print", "Debug",
                        List.of(
                                new PinDefinition("exec_in", "Exec", PinType.EXEC, PinDirection.INPUT, null),
                                new PinDefinition("value", "Value", PinType.ANY, PinDirection.INPUT, null),
                                new PinDefinition("label", "Label", PinType.STRING, PinDirection.INPUT, null),
                                new PinDefinition("exec_out", "Exec", PinType.EXEC, PinDirection.OUTPUT, null)
                        ),
                        Map.of()),
                ctx -> {
                    Object value = ctx.readInputRaw("value");
                    String label = ctx.readInput("label", String.class, "");
                    if (label != null && !label.isEmpty()) {
                        log.info("{}: {}", label, value);
                    } else {
                        log.info("{}", value);
                    }
                    return ExecutionResult.flow("exec_out");
                }
        );
    }
}
