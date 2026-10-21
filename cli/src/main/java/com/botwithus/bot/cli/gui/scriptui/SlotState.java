package com.botwithus.bot.cli.gui.scriptui;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * What the host knows about one script's UI across frames: its live sandbox,
 * and how often its sandboxes have broken a Dear ImGui rule during the
 * script's current run. UI thread only.
 *
 * <p>Failures are counted per run, not per sandbox, because every failure
 * leaks a parked thread and a context: after {@link ScriptUiRenderer#MAX_FAILURES}
 * the host stops offering a reload until the script is restarted, which is
 * what bounds the leak.</p>
 */
final class SlotState {

    /** Dear ImGui's id for no item. */
    private static final int NOT_HELD = 0;

    private final int maxFailures;
    private ScriptUiSandbox sandbox;
    private Instant startedAt;
    private int failures;
    private ImGuiAssertion lastFailure;
    private boolean isStopped;
    private boolean wantsKeyboard;
    /** The panel item held for the host this frame ({@link HostFocus}), or {@link #NOT_HELD}. */
    private int heldItemId = NOT_HELD;
    private boolean hasChromeAnswer;
    private boolean drawsOwnChrome;
    private int lastFrame;
    private int generation;

    SlotState(int maxFailures) {
        this.maxFailures = maxFailures;
    }

    /** Notes that the slot is drawn on host frame {@code frame}. A new run of the script clears its failures. */
    void observe(ScriptUiSlot slot, int frame) {
        lastFrame = frame;
        if (!slot.startedAt().equals(startedAt)) {
            startedAt = slot.startedAt();
            failures = 0;
            lastFailure = null;
            isStopped = false;
        }
    }

    /** The live sandbox, made with {@code create} when there is none. */
    ScriptUiSandbox sandbox(Supplier<ScriptUiSandbox> create) {
        if (sandbox == null) {
            generation++;
            sandbox = create.get();
        }
        return sandbox;
    }

    /** How many sandboxes this state has made, the current one included; part of its thread's name. */
    int generation() {
        return generation;
    }

    /** The sandbox asserted and is abandoned: it is dropped without being disposed. */
    void failed(ImGuiAssertion assertion) {
        sandbox = null;
        failures++;
        lastFailure = assertion;
        isStopped = true;
        wantsKeyboard = false;
    }

    /** True while the panel shows the failure instead of the UI. */
    boolean isStopped() {
        return isStopped;
    }

    boolean canReload() {
        return failures < maxFailures;
    }

    /** Draw the UI again next frame, in a new sandbox. Ignored once the reloads are used up. */
    void reload() {
        if (canReload()) {
            isStopped = false;
        }
    }

    int failures() {
        return failures;
    }

    Optional<ImGuiAssertion> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    /** Notes that {@code itemId} is held for the host until the frame ends. */
    void held(int itemId) {
        heldItemId = itemId;
    }

    /** The item held this frame, if any, which is no longer held once taken. */
    OptionalInt takeHeld() {
        int itemId = heldItemId;
        heldItemId = NOT_HELD;
        return itemId == NOT_HELD ? OptionalInt.empty() : OptionalInt.of(itemId);
    }

    boolean wantsKeyboard() {
        return wantsKeyboard;
    }

    void wantsKeyboard(boolean wants) {
        wantsKeyboard = wants;
    }

    /** True once the UI has answered {@code drawsOwnChrome()} in a sandboxed frame. */
    boolean hasChromeAnswer() {
        return hasChromeAnswer;
    }

    /** The UI's latest {@code drawsOwnChrome()} answer; false before it has given one. */
    boolean drawsOwnChrome() {
        return drawsOwnChrome;
    }

    void drawsOwnChrome(boolean draws) {
        hasChromeAnswer = true;
        drawsOwnChrome = draws;
    }

    /** True when the slot was not drawn in the last {@code frames} frames before {@code frame}. */
    boolean isIdleSince(int frame, int frames) {
        return frame - lastFrame > frames;
    }

    /** Frees a live sandbox; an abandoned one was already dropped. */
    void disposeSandbox() {
        if (sandbox != null) {
            sandbox.dispose();
            sandbox = null;
        }
        wantsKeyboard = false;
    }
}
