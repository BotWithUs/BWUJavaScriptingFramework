package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUI;
import com.botwithus.bot.api.ui.ScriptUiHost;

import imgui.ImGui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which script UIs draw their own window chrome, the handle a UI is attached
 * to, and that both calls into the script happen inside its sandbox, against
 * the real imgui-java natives.
 */
class ScriptUiChromeTest {

    private static final String OWNER = "client/test/script";
    private static final Instant RUN = Instant.parse("2026-10-20T10:00:00Z");
    private static final float PANEL_X = 20f;
    private static final float PANEL_Y = 30f;
    private static final float PANEL_W = 400f;
    private static final float PANEL_H = 300f;

    /** A UI that draws its own chrome. */
    private static final ScriptUI OWN_CHROME = new ScriptUI() {
        @Override
        public void render() {
            ImGui.text("own chrome");
        }

        @Override
        public boolean drawsOwnChrome() {
            return true;
        }
    };

    private HeadlessHost host;
    private ScriptUiRenderer scriptUis;

    @BeforeEach
    void setUp() {
        host = HeadlessHost.create();
        scriptUis = new ScriptUiRenderer(AssertRouter.install(), new TypedChars(), () -> false);
    }

    @AfterEach
    void tearDown() {
        scriptUis.close();
        host.close();
    }

    @Test
    void aUi_drawsNoChromeOfItsOwn_unlessItSaysSo() {
        ScriptUI plain = () -> ImGui.text("plain");

        assertAll(
                () -> assertFalse(askChrome("client/test/plain", plain)),
                () -> assertTrue(askChrome(OWNER, OWN_CHROME)));
    }

    @Test
    void aUiWhoseAnswerThrows_getsTheHostsChrome_andIsStillDrawn() {
        AtomicInteger renders = new AtomicInteger();
        ScriptUI throwing = new ScriptUI() {
            @Override
            public void render() {
                renders.incrementAndGet();
            }

            @Override
            public boolean drawsOwnChrome() {
                throw new IllegalStateException("not ready");
            }
        };

        assertFalse(askChrome(OWNER, throwing));
        draw(slot(throwing, ScriptUiHandle.DOCKED));

        assertFalse(scriptUis.drawsOwnChrome(slot(throwing, ScriptUiHandle.DOCKED)));
        assertEquals(2, renders.get(), "asked once unseen, then drawn once");
        assertFalse(state().isStopped());
    }

    @Test
    void theChromeQuestion_isAskedInTheSandbox_notInTheHostsContext() {
        AtomicLong askedIn = new AtomicLong();
        ScriptUI ui = new ScriptUI() {
            @Override
            public void render() {
            }

            @Override
            public boolean drawsOwnChrome() {
                askedIn.set(ImGui.getCurrentContext().ptr);
                return true;
            }
        };
        long hostContext = ImGui.getCurrentContext().ptr;

        assertTrue(askChrome(OWNER, ui));
        assertNotEquals(0L, askedIn.get());
        assertNotEquals(hostContext, askedIn.get());
    }

    @Test
    void aStoppedUi_getsTheHostsChrome_soItCanStillBeClosed() {
        ScriptUI broken = new ScriptUI() {
            @Override
            public void render() {
                ImGui.popStyleVar();
            }

            @Override
            public boolean drawsOwnChrome() {
                return true;
            }
        };
        draw(slot(broken, ScriptUiHandle.DOCKED));

        assertTrue(state().isStopped());
        assertFalse(askChrome(OWNER, broken));
    }

    @Test
    void attach_runsBeforeEveryRender_withTheSlotsHandle_onTheRenderThread() {
        List<String> calls = new ArrayList<>();
        AtomicReference<ScriptUiHost> attached = new AtomicReference<>();
        AtomicReference<Thread> attachedOn = new AtomicReference<>();
        ScriptUI ui = new ScriptUI() {
            @Override
            public void render() {
                calls.add("render");
            }

            @Override
            public void attach(ScriptUiHost handle) {
                calls.add("attach");
                attached.set(handle);
                attachedOn.set(Thread.currentThread());
            }
        };
        ScriptUiHandle popOut = new ScriptUiHandle(() -> { }, true);

        draw(slot(ui, popOut));
        draw(slot(ui, popOut));

        assertEquals(List.of("attach", "render", "attach", "render"), calls);
        assertSame(popOut, attached.get());
        assertNotEquals(Thread.currentThread(), attachedOn.get(), "attach must run in the sandbox");
    }

    @Test
    void aThrowingAttach_isContainedLikeAThrowingRender() {
        AtomicInteger renders = new AtomicInteger();
        ScriptUI ui = new ScriptUI() {
            @Override
            public void render() {
                renders.incrementAndGet();
            }

            @Override
            public void attach(ScriptUiHost handle) {
                throw new IllegalStateException("attach broke");
            }
        };

        assertDoesNotThrow(() -> draw(slot(ui, ScriptUiHandle.DOCKED)));
        assertDoesNotThrow(() -> draw(slot(ui, ScriptUiHandle.DOCKED)));

        assertEquals(0, renders.get(), "render is skipped when attach throws");
        assertFalse(state().isStopped(), "an exception is not a broken ImGui rule");
        assertEquals(0, state().failures());
    }

    @Test
    void closingFromRender_runsTheHandlesCallback() {
        AtomicBoolean isClosed = new AtomicBoolean(false);
        AtomicReference<ScriptUiHost> attached = new AtomicReference<>();
        ScriptUI ui = new ScriptUI() {
            @Override
            public void render() {
                attached.get().close();
            }

            @Override
            public void attach(ScriptUiHost handle) {
                attached.set(handle);
            }
        };

        draw(slot(ui, new ScriptUiHandle(() -> isClosed.set(true), true)));

        assertTrue(isClosed.get());
    }

    @Test
    void aPoppedOutHandle_closesThroughItsCallback() {
        AtomicBoolean isClosed = new AtomicBoolean(false);
        ScriptUiHost handle = new ScriptUiHandle(() -> isClosed.set(true), true);

        handle.close();

        assertAll(
                () -> assertTrue(handle.poppedOut()),
                () -> assertTrue(isClosed.get()));
    }

    @Test
    void theDockedHandle_isNotPoppedOut_andClosingItDoesNothing() {
        assertAll(
                () -> assertFalse(ScriptUiHandle.DOCKED.poppedOut()),
                () -> assertDoesNotThrow(ScriptUiHandle.DOCKED::close),
                () -> assertSame(ScriptUiHandle.DOCKED, new ScriptUiSlot(OWNER, "s", () -> { }, RUN).host()));
    }

    @Test
    void pinningFromRender_runsTheHandlesPinCallback() {
        AtomicReference<Boolean> pinned = new AtomicReference<>();
        AtomicReference<ScriptUiHost> attached = new AtomicReference<>();
        ScriptUI ui = new ScriptUI() {
            @Override
            public void render() {
                attached.get().setPinnedOverlay(!attached.get().pinnedOverlay());
            }

            @Override
            public void attach(ScriptUiHost handle) {
                attached.set(handle);
            }
        };

        draw(slot(ui, new ScriptUiHandle(() -> { }, true, pinned::set, true, true)));

        assertAll(
                () -> assertEquals(Boolean.FALSE, pinned.get()),
                () -> assertTrue(attached.get().overlayInteractive()));
    }

    @Test
    void aHandleThatCannotPin_neverReadsAsPinned_andPinningItDoesNothing() {
        ScriptUiHost handle = new ScriptUiHandle(() -> { }, true);

        assertDoesNotThrow(() -> handle.setPinnedOverlay(true));
        assertAll(
                () -> assertFalse(handle.pinnedOverlay()),
                () -> assertFalse(handle.overlayInteractive()),
                () -> assertFalse(ScriptUiHandle.DOCKED.pinnedOverlay()));
    }

    private boolean askChrome(String owner, ScriptUI ui) {
        boolean[] answer = new boolean[1];
        host.frame(() -> answer[0] = scriptUis.drawsOwnChrome(new ScriptUiSlot(owner, "script", ui, RUN)));
        scriptUis.endFrame();
        return answer[0];
    }

    private void draw(ScriptUiSlot slot) {
        host.frame(() -> HeadlessHost.panel("##script-ui", PANEL_X, PANEL_Y, PANEL_W, PANEL_H,
                () -> scriptUis.draw(slot)));
        scriptUis.endFrame();
    }

    private static ScriptUiSlot slot(ScriptUI ui, ScriptUiHost handle) {
        return new ScriptUiSlot(OWNER, "script", ui, RUN, handle);
    }

    private SlotState state() {
        return scriptUis.state(OWNER).orElseThrow();
    }
}
