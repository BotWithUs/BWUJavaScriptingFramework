package com.botwithus.bot.api.model;

/**
 * Current walker state from the pipe server.
 *
 * @param state         walker state: idle, computing, walking, interacting, teleporting, arrived, failed
 * @param targetX       target world tile X
 * @param targetY       target world tile Y
 * @param currentStep   current step in the local path segment
 * @param totalSteps    total steps in the local path segment
 * @param navStep       current navigation step (for world paths with multiple segments)
 * @param totalNavSteps total navigation steps
 * @param isWalking     whether the walker is currently active
 * @param isDone        whether the walk has completed
 * @param hpaReady      whether the HPA* graph is ready for world pathfinding
 */
public record WalkStatus(
        String state,
        int targetX,
        int targetY,
        int currentStep,
        int totalSteps,
        int navStep,
        int totalNavSteps,
        boolean isWalking,
        boolean isDone,
        boolean hpaReady
) {}
