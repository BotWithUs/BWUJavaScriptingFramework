package com.botwithus.bot.core.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reports why the agent refused {@code get_component_ops}, once per reason per connection.
 *
 * <p>A refused ops read makes every by-name inventory interact return {@code false}, and a
 * script calls those every loop. Two refusals are worth one WARN because they say why a
 * script stopped working: an agent that predates the method, and a component that does not
 * resolve, which for an inventory grid means its interface is not open. Anything else stays
 * at DEBUG.</p>
 */
final class ComponentOpsRefusals {

    private static final Logger log = LoggerFactory.getLogger(ComponentOpsRefusals.class);

    /** The dispatcher's wording for a method the agent does not have. */
    private static final String UNKNOWN_METHOD = "method not found";
    /** The handler's wording for a component that does not resolve. */
    private static final String NOT_FOUND = "not_found";

    /** The two refusals that get a WARN. */
    enum Reason { AGENT_PREDATES_METHOD, COMPONENT_NOT_OPEN, OTHER }

    private final Set<Reason> warned = ConcurrentHashMap.newKeySet();

    /** Classifies a refusal by the producer's own wording. */
    static Reason classify(String producerMessage) {
        if (producerMessage == null) {
            return Reason.OTHER;
        }
        if (producerMessage.contains(UNKNOWN_METHOD)) {
            return Reason.AGENT_PREDATES_METHOD;
        }
        if (producerMessage.contains(NOT_FOUND)) {
            return Reason.COMPONENT_NOT_OPEN;
        }
        return Reason.OTHER;
    }

    /**
     * Logs one refusal: a WARN the first time a reason is seen on this connection, DEBUG
     * after that and for any other refusal.
     *
     * @return the reason, so a caller or a test can see how it was read
     */
    Reason report(int interfaceId, int componentId, int sub, String producerMessage) {
        Reason reason = classify(producerMessage);
        if (reason != Reason.OTHER && warned.add(reason)) {
            log.warn(warningFor(reason), interfaceId, componentId, sub, producerMessage);
        } else {
            log.debug("get_component_ops {}:{} sub {} refused: {}", interfaceId, componentId, sub,
                    producerMessage);
        }
        return reason;
    }

    /** Whether a WARN has already been spent on {@code reason} on this connection. */
    boolean hasWarned(Reason reason) {
        return warned.contains(reason);
    }

    private static String warningFor(Reason reason) {
        return switch (reason) {
            case AGENT_PREDATES_METHOD -> "get_component_ops {}:{} sub {} refused ({}): the agent"
                    + " predates get_component_ops, so by-name inventory and equipment interacts"
                    + " return false until it is updated. Shown once per connection.";
            case COMPONENT_NOT_OPEN -> "get_component_ops {}:{} sub {} refused ({}): the component"
                    + " does not resolve; for an inventory, open its interface first. By-name"
                    + " interacts return false while it is closed. Shown once per connection.";
            case OTHER -> "get_component_ops {}:{} sub {} refused: {}";
        };
    }
}
