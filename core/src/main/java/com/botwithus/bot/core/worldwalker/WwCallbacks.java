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

    /**
     * Live count of item {@code itemId} the player holds (worn + carried). Used
     * to gate item-requirement teleports (e.g. a dungeoneering cape). Mirrors
     * {@link #readVarbit}: the executor pulls only the ids some requirement
     * references. Return 0 when absent.
     */
    int readItemCount(int itemId);

    /**
     * Batched variant of {@link #readVarbit} invoked at (re-)plan entry, where
     * the executor pulls every varbit referenced by any transition requirement.
     * Implementations must write exactly {@code ids.length} values into
     * {@code outValues} in the same order, using {@code 0} for "absent /
     * unknown".
     *
     * <p>The default fallback loops over the scalar {@link #readVarbit} — fine
     * for test doubles and one-off implementations. The production bridge
     * overrides this to route through {@code api.queryVarbits} so the
     * 25-30 lodestone-unlock varbits collapse to two batched RPC round-trips
     * instead of N synchronous pipe calls (the dominant pre-walk cost).</p>
     */
    default void readVarbits(int[] ids, int[] outValues) {
        for (int i = 0; i < ids.length; i++) {
            outValues[i] = readVarbit(ids[i]);
        }
    }

    /**
     * Batched variant of {@link #readItemCount}. Implementations must write
     * exactly {@code ids.length} counts into {@code outValues} in the same
     * order. The default fallback loops over the scalar
     * {@link #readItemCount}; the production bridge overrides it to read the
     * snapshot once and walk both inventories once instead of N times.
     */
    default void readItemCounts(int[] ids, int[] outValues) {
        for (int i = 0; i < ids.length; i++) {
            outValues[i] = readItemCount(ids[i]);
        }
    }

    /**
     * Whether item {@code itemId} is currently worn (equipped) rather than
     * carried in the backpack. Used to pick the worn-vs-backpack variant of a
     * {@link ChainStepKind#CLICK_ITEM} step.
     */
    boolean isItemWorn(int itemId);

    /**
     * Whether {@code interfaceId} is currently mounted in the engine's
     * open-subs hashmap — the canonical "this interface is open right now"
     * signal. The chain executor polls this between a click that opens a
     * dialog and the click inside that dialog so the inner click only fires
     * once the dialog has actually appeared.
     */
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
     * Fire one host-resolved step of a transition's execution chain. {@code kind}
     * is the {@link ChainStepKind} discriminant; {@code a..i} are its nine
     * generic slots. The executor handles {@code Wait}/{@code WaitInterface}
     * itself, so only these kinds reach the host:
     * <ul>
     *   <li>{@link ChainStepKind#CLICK} — generic queued action {@code (a=actionId,
     *       b..d=param1..3)}; a component click is {@code (COMPONENT, option,
     *       sub, (iface<<16)|comp)}. The executor has already waited for the
     *       target interface (param3>>16 for COMPONENT) to open.</li>
     *   <li>{@link ChainStepKind#DIALOGUE_SELECT} — {@code a=interface, b=index,
     *       c=per_page, d=next_comp, e=wait_ticks}; resolve the option component
     *       against the live (possibly paged) dialogue and click it.</li>
     *   <li>{@link ChainStepKind#CLICK_ITEM} — {@code a..d=worn(iface,comp,opt,
     *       sub)}, {@code e..h=backpack(iface,comp,opt,sub)}, {@code i=backpack
     *       _special}; check whether the teleport item is worn or carried and
     *       dispatch the matching variant.</li>
     * </ul>
     *
     * @param kind the {@link ChainStepKind} wire value
     * @param a..i the nine generic slots (meaning keyed on {@code kind})
     */
    void runChainStep(int kind, int a, int b, int c, int d, int e, int f, int g, int h, int i);

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
