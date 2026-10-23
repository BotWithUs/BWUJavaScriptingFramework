package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUI;

import imgui.ImGui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class ScriptUiSandboxTest {

    private static final float SIZE = 200f;

    @Test
    void anErrorFromRender_comesBackAsCrashed_andTheNextFrameDrawsInAFreshContext() {
        AssertionError thrown = new AssertionError("script bug");
        try (HeadlessHost host = HeadlessHost.create()) {
            ScriptUiSandbox sandbox = new ScriptUiSandbox(ImGui.getIO().getFonts().ptr,
                    new SandboxWorker("script-ui test #1", AssertRouter.install()));
            ScriptUI crashes = () -> {
                throw thrown;
            };
            ScriptUI draws = () -> ImGui.text("fine");
            host.frame(() -> {
                FrameOutcome crashed = sandbox.render(crashes, ScriptUiHandle.DOCKED, input());
                assertSame(thrown, assertInstanceOf(FrameOutcome.Crashed.class, crashed).error());
                assertInstanceOf(FrameOutcome.Drawn.class, sandbox.render(draws, ScriptUiHandle.DOCKED, input()));
            });
            assertFalse(sandbox.isAbandoned());
            sandbox.dispose();
        }
    }

    @Test
    void anAssertion_isDescribedByItsExpressionAndFileBaseName() {
        ImGuiAssertion assertion = new ImGuiAssertion("g.GroupStack.Size > 0", "/tmp/imgui/jni/imgui.cpp", 11302);

        assertEquals("g.GroupStack.Size > 0 (imgui.cpp:11302)", assertion.describe());
        assertEquals("x (imgui.h:7)", new ImGuiAssertion("x", "C:\\build\\imgui.h", 7).describe());
    }

    @Test
    void typedChars_areTakenOnce_andGrowPastTheirInitialCapacity() {
        TypedChars typed = new TypedChars();
        int[] many = new int[40];
        Arrays.setAll(many, i -> 'a' + i % 26);
        for (int c : many) {
            typed.typed(c);
        }

        assertArrayEquals(many, typed.take());
        assertEquals(0, typed.take().length);
        typed.typed('x');
        typed.clear();
        assertEquals(0, typed.take().length);
    }

    private static FrameInput input() {
        StyleSnapshot style = new StyleSnapshot();
        style.readFrom(ImGui.getStyle());
        return new FrameInput(SIZE, SIZE, 1f / 60f, ImGui.getFont().ptr, 1f, FrameInput.Pointer.AWAY,
                FrameInput.Keyboard.NONE, style);
    }
}
