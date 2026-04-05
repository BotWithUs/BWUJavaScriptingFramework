package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.model.ActionEntry;
import com.botwithus.bot.api.model.GameAction;

import java.util.List;

/**
 * Game action queuing and execution control.
 *
 * @see com.botwithus.bot.api.GameAPI
 */
public interface ActionAPI {

    /**
     * Queues a single game action for execution on the next game tick.
     *
     * @param action the action to queue
     */
    void queueAction(GameAction action);

    /**
     * Queues multiple game actions for execution.
     *
     * @param actions the actions to queue
     * @return the number of actions successfully queued
     */
    int queueActions(List<GameAction> actions);

    /**
     * Returns the number of actions currently pending in the queue.
     *
     * @return the queue size
     */
    int getActionQueueSize();

    /**
     * Clears all pending actions from the queue.
     */
    void clearActionQueue();

    /**
     * Returns a history of recently executed actions.
     *
     * @param maxResults      maximum number of entries to return
     * @param actionIdFilter  filter by action ID, or {@code -1} for all
     * @return a list of historical action entries
     */
    List<ActionEntry> getActionHistory(int maxResults, int actionIdFilter);

    /**
     * Returns the game-cycle timestamp of the last executed action.
     *
     * @return the timestamp in milliseconds
     */
    long getLastActionTime();

    /**
     * Sets a behavior modifier value that adjusts action execution.
     *
     * @param modId the modifier identifier
     * @param value the modifier value
     */
    void setBehaviorMod(int modId, float value);

    /**
     * Clears a previously set behavior modifier.
     *
     * @param modId the modifier identifier to clear
     */
    void clearBehaviorMod(int modId);

    /**
     * Returns the current value of a behavior modifier.
     *
     * @param modId the modifier identifier
     * @return the modifier value, or {@code 0.0f} if not set
     */
    float getBehaviorMod(int modId);

    /**
     * Checks whether action queuing is currently blocked.
     *
     * @return {@code true} if actions are blocked
     */
    boolean areActionsBlocked();

    /**
     * Enables or disables action blocking.
     *
     * @param blocked {@code true} to block actions, {@code false} to unblock
     */
    void setActionsBlocked(boolean blocked);
}
