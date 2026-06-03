package com.botwithus.bot.core.worldwalker;

/**
 * Host-supplied callback surface invoked by the C executor for the lifetime of
 * a single {@link WorldWalker#runExecutor} call. Mirrors the {@code WwCallbacks}
 * vtable in {@code worldwalker_c.h} — every method here corresponds to one of
 * the ten function-pointer slots on that struct.
 *
 * <h2>Three call categories</h2>
 * <ul>
 *   <li><b>Reads</b> ({@link #readPosition}, {@link #readCapability},
 *       {@link #readVarbit}, {@link #isInterfaceOpen}) are pulled live by the
 *       executor and must be cheap and side-effect-free.</li>
 *   <li><b>Actions</b> ({@link #walkTo}, {@link #interact},
 *       {@link #runChainStep}, {@link #sleepTicks}) are fire-and-forget; the
 *       executor sequences them around reads to detect arrival, drift, and
 *       stuck conditions.</li>
 *   <li><b>Control</b> + <b>progress</b> ({@link #shouldCancel},
 *       {@link #onEvent}) gate abort and surface narration.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Every method is called from the thread that invoked {@code runExecutor} (the
 * native executor blocks that thread end-to-end). Implementations therefore
 * don't need to be thread-safe for cross-thread access — but they MUST NOT
 * recursively call back into {@code runExecutor} on the same instance, and
 * they MUST NOT block indefinitely (the native side has stuck-deadlines but no
 * watchdog over arbitrary host code).
 *
 * <h2>Exception handling</h2>
 * If any method throws, the executor is aborted: the run returns
 * {@link WwStatus#FAILED} (or {@link WwStatus#CANCELLED} if cancellation also
 * fired) and {@code runExecutor} rethrows the original {@link Throwable} on
 * the calling thread. Exceptions never propagate through native frames.
 */
public interface WwCallbacks {

    // ── Reads ──────────────────────────────────────────────────────────────

    /** Live player tile. Called frequently; must be cheap. */
    WwTile readPosition();

    /**
     * Live capability snapshot at the start of every (re-)plan. Return
     * {@code null} or {@link CapabilitySnapshot#empty()} to admit every
     * requirement-gated transition.
     */
    CapabilitySnapshot readCapability();

    /** Read one varbit by id. */
    int readVarbit(int id);

    /** Whether the given interface id is currently open. */
    boolean isInterfaceOpen(int interfaceId);

    // ── Actions ────────────────────────────────────────────────────────────

    /** Initiate a walk toward {@code target}; the executor polls position for arrival. */
    void walkTo(WwTile target);

    /**
     * Interact with the loc {@code objectId} at {@code tile}, choosing the
     * given option index.
     *
     * @return {@code 1} if a game action was actually queued, {@code 0} if the
     *         call was a no-op — the baked loc is absent from the live scene,
     *         which for a door means it is already open. The executor uses this
     *         to skip its post-action settle wait when nothing was issued, so an
     *         already-open door flows through instead of pausing.
     */
    int interact(int objectId, WwTile tile, int optionIndex);

    /**
     * Fire one {@code Click} step of a transition's execution chain — e.g. a
     * lodestone-network or spell teleport. The executor has already waited for
     * {@code interfaceId} to be open before calling, so the implementation only
     * needs to issue the component interaction.
     *
     * @param interfaceId the interface (window) id holding the component
     * @param componentId the component within the interface to click
     * @param optionId    the menu option index on that component
     */
    void runChainStep(int interfaceId, int componentId, int optionId);

    /** Sleep for the given number of game ticks (~600ms each). */
    void sleepTicks(int ticks);

    // ── Control ────────────────────────────────────────────────────────────

    /**
     * Polled each executor loop turn. Returning {@code true} aborts the run
     * with {@link WwStatus#CANCELLED} at the next safe point.
     */
    boolean shouldCancel();

    // ── Progress ───────────────────────────────────────────────────────────

    /**
     * Optional progress hook. Defaults to a no-op; override to log or surface
     * events to a UI. Implementations must NOT retain the {@link WwEvent}
     * past a long-running operation — the record is heap-resident and safe
     * to keep, but the executor moves on immediately after the call returns.
     */
    default void onEvent(WwEvent event) {
        // no-op
    }
}
