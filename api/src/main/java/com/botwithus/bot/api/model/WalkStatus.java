package com.botwithus.bot.api.model;

/**
 * Current walker state.
 *
 * <p>The state is computed host-side by the walker that drives the character,
 * not reported by the game — so it describes what this host is trying to do,
 * and is scoped to the script that asked. See
 * {@link com.botwithus.bot.api.domain.NavigationAPI#getWalkStatus()}.</p>
 *
 * @param state         walker state, one of: {@code idle} (this script has not
 *                      asked for a walk), {@code walking}, {@code arrived},
 *                      {@code cancelled}, {@code failed}, or
 *                      {@code refused_busy} (another script was already walking
 *                      this character, so nothing was started). Treat an
 *                      unrecognised value as non-terminal rather than
 *                      switching exhaustively — states are added additively
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
