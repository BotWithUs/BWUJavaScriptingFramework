package com.botwithus.bot.api.model;

/**
 * Result of a blocking walk operation.
 */
public enum WalkResult {
    /** The walker reached the destination. */
    ARRIVED,
    /** The walk was cancelled (by another action or explicit cancel). */
    CANCELLED,
    /** The walk failed (pathfinding failure, stuck, etc.). */
    FAILED,
    /** The walk timed out waiting for a result. */
    TIMEOUT
}
