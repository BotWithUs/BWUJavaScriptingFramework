package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUI;

import imgui.ImGui;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

/**
 * Runs one {@link Breaker} in a JVM of its own, so the exit code is the result:
 * {@code BreakerMain <BREAKER> guarded|unguarded}.
 *
 * <ul>
 *   <li>{@code unguarded} draws the breaker straight into the host's context,
 *       inside the drawer's child, exactly as master's
 *       {@code ConfigInspector.drawScriptUi} did, with imgui-java's own
 *       assertion handler. A fatal breaker exits 1 from inside imgui-java.</li>
 *   <li>{@code guarded} draws it through {@link ScriptUiHost}, next to a second,
 *       well-behaved script UI in a panel of its own.</li>
 * </ul>
 *
 * <p>Either way it runs {@link #FRAMES} host frames and then prints
 * {@code RESULT key=value} lines for the test to read.</p>
 */
public final class BreakerMain {

    static final int FRAMES = 4;
    static final String GUARDED = "guarded";
    static final String UNGUARDED = "unguarded";
    /**
     * Guarded, but the host first hands Dear ImGui an ini path the way it did
     * before {@code HostIni}. That arms imgui-java's process-wide "free the old
     * string" flag, so the sandbox's own {@code setIniFilename(null)} frees a
     * string literal: the process dies of heap corruption.
     */
    static final String INI_VIA_IO = "ini-via-io";

    private static final float PANEL_X = 20f;
    private static final float PANEL_Y = 20f;
    private static final float PANEL_W = 500f;
    private static final float PANEL_H = 300f;
    private static final float GAP = 40f;
    private static final String BROKEN_OWNER = "client/test/breaker";
    private static final String GOOD_OWNER = "client/test/good";

    private BreakerMain() {
    }

    public static void main(String[] args) {
        Breaker breaker = Breaker.valueOf(args[0]);
        boolean isGuarded = !UNGUARDED.equals(args[1]);
        PrintStream out = System.out;
        int[] goodFrames = {0};
        ScriptUI good = () -> {
            ImGui.text("good UI");
            ImGui.button("press");
            goodFrames[0]++;
        };
        try (HeadlessHost host = HeadlessHost.create()) {
            if (INI_VIA_IO.equals(args[1])) {
                ImGui.getIO().setIniFilename(Path.of("build", "ini-via-io-never-written.ini").toString());
            }
            ScriptUiHost scriptUis = isGuarded
                    ? new ScriptUiHost(AssertRouter.install(), new TypedChars(), () -> ImGui.button("Reload UI"))
                    : null;
            for (int frame = 0; frame < FRAMES; frame++) {
                host.frame(() -> drawPanels(breaker.ui(), good, scriptUis));
                if (scriptUis != null) {
                    scriptUis.endFrame();
                }
            }
            out.println("RESULT frames=" + FRAMES);
            out.println("RESULT good_frames=" + goodFrames[0]);
            if (scriptUis != null) {
                report(out, scriptUis);
                scriptUis.close();
            }
        }
        out.flush();
    }

    private static void drawPanels(ScriptUI broken, ScriptUI good, ScriptUiHost scriptUis) {
        HeadlessHost.panel("##script-ui", PANEL_X, PANEL_Y, PANEL_W, PANEL_H, () -> draw(broken, BROKEN_OWNER,
                scriptUis));
        HeadlessHost.panel("##script-ui-good", PANEL_X, PANEL_Y + PANEL_H + GAP, PANEL_W, PANEL_H,
                () -> draw(good, GOOD_OWNER, scriptUis));
    }

    private static void draw(ScriptUI ui, String owner, ScriptUiHost scriptUis) {
        if (scriptUis != null) {
            scriptUis.draw(new ScriptUiSlot(owner, owner, ui, Instant.EPOCH));
            return;
        }
        try {
            ui.render();
        } catch (RuntimeException e) {
            ImGui.textWrapped("This script's UI failed to draw: " + e.getMessage());
        }
    }

    private static void report(PrintStream out, ScriptUiHost scriptUis) {
        SlotState broken = scriptUis.state(BROKEN_OWNER).orElseThrow();
        out.println("RESULT stopped=" + broken.isStopped());
        out.println("RESULT failures=" + broken.failures());
        broken.lastFailure().ifPresent(a -> out.println("RESULT assertion=" + a.describe()));
        out.println("RESULT good_stopped=" + scriptUis.state(GOOD_OWNER).orElseThrow().isStopped());
        out.println("RESULT ui_threads=" + parkedScriptUiThreads().toLowerCase(Locale.ROOT));
    }

    /** Names of live threads that render script UIs, as a thread dump would show them. */
    private static String parkedScriptUiThreads() {
        StringBuilder names = new StringBuilder();
        Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith("script-ui "))
                .sorted()
                .forEach(name -> names.append('[').append(name).append(']'));
        return names.toString();
    }
}
