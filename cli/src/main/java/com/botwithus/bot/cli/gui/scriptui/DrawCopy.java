package com.botwithus.bot.cli.gui.scriptui;

import imgui.ImDrawData;
import imgui.ImDrawList;
import imgui.ImVec4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * One sandboxed frame's draw data, copied out of the sandbox's context so the
 * UI thread can add it to the host's draw list after the sandbox is done.
 *
 * <p>The copy is needed because imgui-java hands vertex and index data back in
 * one static buffer that the next call overwrites. Compositing de-indexes:
 * every index becomes its own vertex in the host list, written with
 * {@link ImDrawList#primVtx}, which pairs each vertex with the next index.
 * Clip rects are intersected with the host's current one, so the script's UI,
 * including its popups and tooltips, never draws outside its panel.</p>
 *
 * @param lists one entry per sandbox draw list, in draw order
 */
record DrawCopy(List<ListCopy> lists) {

    /** {@code ImDrawVert}: pos (2 floats), uv (2 floats), col (packed u32). */
    private static final int POS_X = 0;
    private static final int POS_Y = 4;
    private static final int UV_X = 8;
    private static final int UV_Y = 12;
    private static final int COL = 16;
    /** Size in bytes of a 16-bit {@code ImDrawIdx}, the build's default. */
    private static final int SHORT_INDEX = 2;
    private static final int U16_MASK = 0xFFFF;
    /** Vertices per {@code primReserve}: a whole number of triangles, well under 16-bit indices. */
    private static final int CHUNK_VERTICES = 3 * 10_000;

    DrawCopy {
        lists = List.copyOf(lists);
    }

    /** One {@code ImDrawCmd}: its clip rect in sandbox coordinates and the slice of indices it draws. */
    record Command(float clipMinX, float clipMinY, float clipMaxX, float clipMaxY,
                   long textureId, int elemCount, int idxOffset, int vtxOffset) {
    }

    /** One {@code ImDrawList}: its vertex and index bytes, and its commands. */
    record ListCopy(ByteBuffer vertices, ByteBuffer indices, List<Command> commands) {

        ListCopy {
            commands = List.copyOf(commands);
        }
    }

    /** Copies {@code data}. Call with the sandbox's context current, right after {@code ImGui.render()}. */
    static DrawCopy capture(ImDrawData data) {
        List<ListCopy> lists = new ArrayList<>();
        for (int list = 0; list < data.getCmdListsCount(); list++) {
            lists.add(new ListCopy(copy(data.getCmdListVtxBufferData(list)),
                    copy(data.getCmdListIdxBufferData(list)), commands(data, list)));
        }
        return new DrawCopy(lists);
    }

    /** Total vertices the composite writes: one per index. */
    int compositeVertexCount() {
        int total = 0;
        for (ListCopy list : lists) {
            for (Command command : list.commands()) {
                total += command.elemCount();
            }
        }
        return total;
    }

    /** Adds every triangle to {@code target}, moved by ({@code originX}, {@code originY}). UI thread. */
    void compositeInto(ImDrawList target, float originX, float originY) {
        int vertexSize = ImDrawData.sizeOfImDrawVert();
        int indexSize = ImDrawData.sizeOfImDrawIdx();
        for (ListCopy list : lists) {
            for (Command command : list.commands()) {
                if (command.elemCount() > 0) {
                    target.pushClipRect(command.clipMinX() + originX, command.clipMinY() + originY,
                            command.clipMaxX() + originX, command.clipMaxY() + originY, true);
                    target.pushTextureID(command.textureId());
                    writeTriangles(target, list, command, new Layout(vertexSize, indexSize, originX, originY));
                    target.popTextureID();
                    target.popClipRect();
                }
            }
        }
    }

    /** The byte layout of the copied buffers and the offset to apply. */
    private record Layout(int vertexSize, int indexSize, float originX, float originY) {
    }

    private static void writeTriangles(ImDrawList target, ListCopy list, Command command, Layout layout) {
        for (int start = 0; start < command.elemCount(); start += CHUNK_VERTICES) {
            int count = Math.min(CHUNK_VERTICES, command.elemCount() - start);
            target.primReserve(count, count);
            for (int i = 0; i < count; i++) {
                int index = readIndex(list.indices(), command.idxOffset() + start + i, layout.indexSize());
                int at = (command.vtxOffset() + index) * layout.vertexSize();
                ByteBuffer v = list.vertices();
                target.primVtx(v.getFloat(at + POS_X) + layout.originX(), v.getFloat(at + POS_Y) + layout.originY(),
                        v.getFloat(at + UV_X), v.getFloat(at + UV_Y), v.getInt(at + COL));
            }
        }
    }

    private static int readIndex(ByteBuffer indices, int position, int indexSize) {
        if (indexSize == SHORT_INDEX) {
            return indices.getShort(position * SHORT_INDEX) & U16_MASK;
        }
        return indices.getInt(position * indexSize);
    }

    private static List<Command> commands(ImDrawData data, int list) {
        List<Command> commands = new ArrayList<>();
        ImVec4 clip = new ImVec4();
        for (int cmd = 0; cmd < data.getCmdListCmdBufferSize(list); cmd++) {
            data.getCmdListCmdBufferClipRect(clip, list, cmd);
            commands.add(new Command(clip.x, clip.y, clip.z, clip.w,
                    data.getCmdListCmdBufferTextureId(list, cmd), data.getCmdListCmdBufferElemCount(list, cmd),
                    data.getCmdListCmdBufferIdxOffset(list, cmd), data.getCmdListCmdBufferVtxOffset(list, cmd)));
        }
        return commands;
    }

    /** A heap copy of imgui-java's shared buffer, which the next call overwrites. */
    private static ByteBuffer copy(ByteBuffer shared) {
        ByteBuffer copy = ByteBuffer.allocate(shared.remaining()).order(ByteOrder.nativeOrder());
        copy.put(shared.duplicate());
        return copy.flip();
    }
}
