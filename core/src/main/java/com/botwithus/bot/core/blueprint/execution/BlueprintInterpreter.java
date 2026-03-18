package com.botwithus.bot.core.blueprint.execution;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.blueprint.*;
import com.botwithus.bot.core.blueprint.registry.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tree-walking interpreter for blueprint graphs.
 * Executes node chains by following exec pin connections, resolving data inputs
 * lazily via a pull model, and caching outputs per frame.
 */
public class BlueprintInterpreter {

    private static final Logger log = LoggerFactory.getLogger(BlueprintInterpreter.class);
    private final BlueprintGraph graph;
    private final NodeRegistry registry;
    private final GameAPI api;
    private final Map<String, Object> variables = new HashMap<>();
    private final Map<Long, Map<String, Object>> frameCache = new HashMap<>();
    private int lastDelay = 600;

    /**
     * Creates a new interpreter for the given graph.
     *
     * @param graph    the blueprint graph to execute
     * @param registry the node registry with definitions and executors
     * @param api      the game API instance
     */
    public BlueprintInterpreter(BlueprintGraph graph, NodeRegistry registry, GameAPI api) {
        this.graph = graph;
        this.registry = registry;
        this.api = api;
    }

    /**
     * Executes the blueprint starting from the node with the given entry type ID.
     * Finds the first node matching the type, executes it, and follows the exec pin chain.
     *
     * @param entryNodeTypeId the type ID of the entry node (e.g. "flow.onStart", "flow.onLoop")
     * @return the loop delay in ms (from delay outputs), or 600 as default
     */
    public int executeFrom(String entryNodeTypeId) {
        NodeInstance entryNode = findNodeByType(entryNodeTypeId);
        if (entryNode == null) {
            return lastDelay;
        }

        lastDelay = 600;
        ExecutionResult result = executeNode(entryNode);
        if (result != null) {
            extractDelay(result);
            if (result.nextExecPin() != null) {
                followExecChain(entryNode, result.nextExecPin());
            }
        }
        return lastDelay;
    }

    /**
     * Follows the execution chain from a given node's exec output pin.
     *
     * @param sourceNode  the node whose exec pin to follow
     * @param execPinId   the exec output pin ID on the source node
     */
    private void followExecChain(NodeInstance sourceNode, String execPinId) {
        NodeInstance currentNode = sourceNode;
        String currentExecPin = execPinId;

        while (currentExecPin != null && currentNode != null) {
            // Find the pin index for the exec output
            NodeDefinition def = registry.getDefinition(currentNode.getTypeId());
            if (def == null) break;

            int pinIndex = findPinIndex(def, currentExecPin, PinDirection.OUTPUT);
            if (pinIndex < 0) break;

            long sourcePinId = currentNode.pinId(pinIndex);
            List<Link> links = graph.findLinksFromPin(sourcePinId);
            if (links.isEmpty()) break;

            // Follow the first exec link
            Link link = links.getFirst();
            long targetPinId = link.targetPinId();
            long targetNodeId = targetPinId / 1000;
            NodeInstance targetNode = graph.findNode(targetNodeId);
            if (targetNode == null) break;

            // Execute the target node
            ExecutionResult result = executeNode(targetNode);
            if (result == null) break;

            extractDelay(result);
            currentNode = targetNode;
            currentExecPin = result.nextExecPin();
        }
    }

    /**
     * Executes a single node: resolves its inputs, runs the executor, and caches outputs.
     *
     * @param node the node instance to execute
     * @return the execution result, or {@code null} on error
     */
    private ExecutionResult executeNode(NodeInstance node) {
        String typeId = node.getTypeId();
        NodeExecutor executor = registry.getExecutor(typeId);
        NodeDefinition def = registry.getDefinition(typeId);
        if (executor == null || def == null) {
            log.error("No executor for node type: {}", typeId);
            return null;
        }

        try {
            // Create execution context with input resolver
            ExecutionContext ctx = new ExecutionContext(api, node, pinId -> resolveInput(node, def, pinId), variables);

            // Provide loop body executor for loop/sequence nodes
            ctx.setLoopBodyExecutor(execPinId -> followExecChain(node, execPinId));

            ExecutionResult result = executor.execute(ctx);

            // Merge context outputs with result outputs
            Map<String, Object> allOutputs = new HashMap<>(result.outputs());
            allOutputs.putAll(ctx.getOutputs());

            // Cache outputs for this node
            frameCache.put(node.getId(), allOutputs);

            return new ExecutionResult(allOutputs, result.nextExecPin());
        } catch (Exception e) {
            log.error("Error executing node {} (id={}): {}", typeId, node.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Resolves an input pin value by walking back through links to the source output pin.
     * If the source node hasn't been executed yet, executes it lazily and caches the result.
     *
     * @param node  the node whose input is being resolved
     * @param def   the node definition
     * @param pinId the input pin ID
     * @return the resolved value, or the pin's default value if not connected
     */
    private Object resolveInput(NodeInstance node, NodeDefinition def, String pinId) {
        // Find the input pin index
        int pinIndex = findPinIndex(def, pinId, PinDirection.INPUT);
        if (pinIndex < 0) {
            // Try property values as fallback
            return node.getProperty(pinId);
        }

        long targetPinGlobalId = node.pinId(pinIndex);
        Link link = graph.findLinkToPin(targetPinGlobalId);

        if (link == null) {
            // No connection - return default value from pin definition
            PinDefinition pinDef = def.pins().get(pinIndex);
            if (pinDef.defaultValue() != null) {
                return pinDef.defaultValue();
            }
            // Check node properties as fallback
            Object prop = node.getProperty(pinId);
            return prop;
        }

        // Find the source node and pin
        long sourcePinGlobalId = link.sourcePinId();
        long sourceNodeId = sourcePinGlobalId / 1000;
        NodeInstance sourceNode = graph.findNode(sourceNodeId);
        if (sourceNode == null) return null;

        // Check if already cached
        Map<String, Object> cachedOutputs = frameCache.get(sourceNodeId);
        if (cachedOutputs == null) {
            // Lazily execute the source data node
            ExecutionResult result = executeNode(sourceNode);
            if (result == null) return null;
            cachedOutputs = frameCache.get(sourceNodeId);
            if (cachedOutputs == null) return null;
        }

        // Find the source pin ID string
        NodeDefinition sourceDef = registry.getDefinition(sourceNode.getTypeId());
        if (sourceDef == null) return null;

        int sourcePinIndex = (int) (sourcePinGlobalId - sourceNodeId * 1000);
        if (sourcePinIndex < 0 || sourcePinIndex >= sourceDef.pins().size()) return null;

        String sourcePinIdStr = sourceDef.pins().get(sourcePinIndex).id();
        return cachedOutputs.get(sourcePinIdStr);
    }

    /**
     * Finds the index of a pin by ID and direction within a node definition.
     *
     * @param def       the node definition
     * @param pinId     the pin ID string
     * @param direction the expected pin direction
     * @return the pin index, or -1 if not found
     */
    private int findPinIndex(NodeDefinition def, String pinId, PinDirection direction) {
        List<PinDefinition> pins = def.pins();
        for (int i = 0; i < pins.size(); i++) {
            PinDefinition pin = pins.get(i);
            if (pin.id().equals(pinId) && pin.direction() == direction) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Finds the first node instance matching the given type ID.
     *
     * @param typeId the node type ID to search for
     * @return the first matching node, or {@code null}
     */
    private NodeInstance findNodeByType(String typeId) {
        for (NodeInstance node : graph.getNodes()) {
            if (typeId.equals(node.getTypeId())) {
                return node;
            }
        }
        return null;
    }

    /**
     * Extracts delay value from execution result outputs if present.
     *
     * @param result the execution result to check
     */
    private void extractDelay(ExecutionResult result) {
        Object delay = result.outputs().get("delay");
        if (delay instanceof Number n) {
            int d = n.intValue();
            if (d > 0) {
                lastDelay = d;
            }
        }
    }

    /**
     * Clears the per-frame output cache. Call between loop iterations.
     */
    public void clearFrameCache() {
        frameCache.clear();
    }

    /**
     * Sets a blueprint-level variable.
     *
     * @param name  the variable name
     * @param value the variable value
     */
    private static final int MAX_VARIABLES = 10_000;

    public void setVariable(String name, Object value) {
        if (variables.size() >= MAX_VARIABLES && !variables.containsKey(name)) {
            log.warn("Variable limit reached ({}), ignoring: {}", MAX_VARIABLES, name);
            return;
        }
        variables.put(name, value);
    }

    /**
     * Returns a blueprint-level variable value.
     *
     * @param name the variable name
     * @return the variable value, or {@code null}
     */
    public Object getVariable(String name) {
        return variables.get(name);
    }
}
