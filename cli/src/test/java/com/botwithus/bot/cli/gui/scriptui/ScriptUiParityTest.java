package com.botwithus.bot.cli.gui.scriptui;

import com.botwithus.bot.api.ui.ScriptUI;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A well-behaved script UI must look exactly as it did before sandboxing. The
 * same UI is drawn straight into the host's context, inside the drawer's child
 * as master does, and through {@link ScriptUiRenderer}; both frames are flattened to
 * vertices, clip rects and textures, and must match.
 *
 * <p>A failure is any vertex that differs in position (beyond {@link #TOLERANCE}),
 * texture coordinate, colour, texture or clip rect, or a different vertex count.</p>
 */
class ScriptUiParityTest {

    private static final int FRAMES = 3;
    private static final float TOLERANCE = 1e-3f;
    private static final float PANEL_X = 37f;
    private static final float PANEL_Y = 53f;
    private static final float PANEL_W = 520f;
    private static final float PANEL_H = 480f;
    /** Enough geometry that a match is not a coincidence of two near-empty frames. */
    private static final int MIN_VERTICES = 2_000;
    private static final float SLIDER_MAX = 10f;
    private static final float WRAP_AT = 180f;
    private static final float INNER_W = 240f;
    private static final float INNER_H = 90f;
    private static final float PROGRESS = 0.4f;
    private static final int INNER_LINES = 6;
    private static final int TINT = 0xFF33AAFF;

    private final ImBoolean checked = new ImBoolean(true);
    private final float[] slider = {3.5f};
    private final ImString input = new ImString("typed text", 64);

    @Test
    void aWellBehavedUi_drawsTheSameThroughTheSandbox() {
        List<Triangles.Vertex> direct = lastFrame(ui -> ui.render());
        List<Triangles.Vertex> sandboxed;
        AssertRouter router = AssertRouter.install();
        ScriptUiRenderer[] host = new ScriptUiRenderer[1];
        sandboxed = lastFrame(ui -> {
            if (host[0] == null) {
                host[0] = new ScriptUiRenderer(router, new TypedChars(), () -> false);
            }
            host[0].draw(new ScriptUiSlot("client/test/reference", "reference", ui, Instant.EPOCH));
        });
        host[0].close();

        assertTrue(direct.size() >= MIN_VERTICES, "reference UI drew only " + direct.size() + " vertices");
        assertEquals(direct.size(), sandboxed.size(), "vertex count");
        for (int i = 0; i < direct.size(); i++) {
            assertSame(i, direct.get(i), sandboxed.get(i));
        }
    }

    /** {@code FRAMES} host frames with the reference UI drawn by {@code draw}; the last one, flattened. */
    private List<Triangles.Vertex> lastFrame(Consumer<ScriptUI> draw) {
        try (HeadlessHost host = HeadlessHost.create()) {
            List<Triangles.Vertex> last = List.of();
            for (int frame = 0; frame < FRAMES; frame++) {
                last = Triangles.of(host.frame(() -> HeadlessHost.panel("##script-ui", PANEL_X, PANEL_Y, PANEL_W,
                        PANEL_H, () -> draw.accept(this::reference))));
            }
            return last;
        }
    }

    private static void assertSame(int i, Triangles.Vertex a, Triangles.Vertex b) {
        String at = "vertex " + i + ": " + a + " vs " + b;
        assertEquals(a.x(), b.x(), TOLERANCE, at);
        assertEquals(a.y(), b.y(), TOLERANCE, at);
        assertEquals(a.u(), b.u(), at);
        assertEquals(a.v(), b.v(), at);
        assertEquals(a.col(), b.col(), at);
        assertEquals(a.texture(), b.texture(), at);
        assertEquals(a.clipMinX(), b.clipMinX(), TOLERANCE, at);
        assertEquals(a.clipMinY(), b.clipMinY(), TOLERANCE, at);
        assertEquals(a.clipMaxX(), b.clipMaxX(), TOLERANCE, at);
        assertEquals(a.clipMaxY(), b.clipMaxY(), TOLERANCE, at);
    }

    /** Most of what published scripts draw: text, buttons, inputs, style pushes, a child, a group, a combo. */
    private void reference() {
        ImGui.text("Reference UI");
        ImGui.textWrapped("A longer line of text that has to wrap at the edge of the panel it is drawn in,"
                + " the way a script's description usually does.");
        ImGui.button("Button");
        ImGui.sameLine();
        ImGui.smallButton("small");
        ImGui.checkbox("Check", checked);
        ImGui.sliderFloat("Slider", slider, 0f, SLIDER_MAX);
        ImGui.inputText("Input", input);
        ImGui.separator();
        ImGui.pushStyleColor(ImGuiCol.Text, TINT);
        ImGui.text("tinted");
        ImGui.popStyleColor();
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 8f, 6f);
        ImGui.button("padded");
        ImGui.popStyleVar();
        referenceLayout();
    }

    private void referenceLayout() {
        ImGui.beginGroup();
        ImGui.text("grouped one");
        ImGui.text("grouped two");
        ImGui.endGroup();
        ImGui.sameLine();
        ImGui.text("beside the group");
        ImGui.beginChild("inner", INNER_W, INNER_H, true);
        for (int line = 0; line < INNER_LINES; line++) {
            ImGui.text("inner line " + line);
        }
        ImGui.endChild();
        if (ImGui.beginCombo("Combo", "first")) {
            ImGui.selectable("first");
            ImGui.endCombo();
        }
        ImGui.progressBar(PROGRESS);
        ImGui.pushTextWrapPos(ImGui.getCursorPosX() + WRAP_AT);
        ImGui.text("wrapped at a pushed position, which the text-wrap stack carries");
        ImGui.popTextWrapPos();
        ImGui.beginDisabled();
        ImGui.button("disabled");
        ImGui.endDisabled();
        ImGui.getWindowDrawList().addRect(ImGui.getCursorScreenPosX(), ImGui.getCursorScreenPosY(),
                ImGui.getCursorScreenPosX() + WRAP_AT, ImGui.getCursorScreenPosY() + INNER_H, TINT);
    }
}
