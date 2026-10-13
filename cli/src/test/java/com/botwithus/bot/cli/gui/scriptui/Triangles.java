package com.botwithus.bot.cli.gui.scriptui;

import imgui.ImDrawData;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A frame's draw data flattened to what reaches the screen: every vertex, in
 * draw order, with the clip rect and texture it is drawn under. Two frames that
 * flatten to the same list put the same pixels on the screen, however their
 * commands and draw lists happen to be split.
 */
final class Triangles {

    private static final int POS_X = 0;
    private static final int POS_Y = 4;
    private static final int UV_X = 8;
    private static final int UV_Y = 12;
    private static final int COL = 16;
    private static final int SHORT_INDEX = 2;
    private static final int U16_MASK = 0xFFFF;

    /** One drawn vertex and the state it is drawn under. */
    record Vertex(float clipMinX, float clipMinY, float clipMaxX, float clipMaxY, long texture,
                  float x, float y, float u, float v, int col) {
    }

    private Triangles() {
    }

    /** Flattens {@code data}. Call with its context current, right after {@code ImGui.render()}. */
    static List<Vertex> of(ImDrawData data) {
        List<Vertex> vertices = new ArrayList<>();
        int vertexSize = ImDrawData.sizeOfImDrawVert();
        for (DrawCopy.ListCopy list : DrawCopy.capture(data).lists()) {
            for (DrawCopy.Command cmd : list.commands()) {
                for (int i = 0; i < cmd.elemCount(); i++) {
                    int index = list.indices().getShort((cmd.idxOffset() + i) * SHORT_INDEX) & U16_MASK;
                    vertices.add(vertex(list.vertices(), (cmd.vtxOffset() + index) * vertexSize, cmd));
                }
            }
        }
        return vertices;
    }

    private static Vertex vertex(ByteBuffer v, int at, DrawCopy.Command cmd) {
        return new Vertex(cmd.clipMinX(), cmd.clipMinY(), cmd.clipMaxX(), cmd.clipMaxY(), cmd.textureId(),
                v.getFloat(at + POS_X), v.getFloat(at + POS_Y), v.getFloat(at + UV_X), v.getFloat(at + UV_Y),
                v.getInt(at + COL));
    }
}
