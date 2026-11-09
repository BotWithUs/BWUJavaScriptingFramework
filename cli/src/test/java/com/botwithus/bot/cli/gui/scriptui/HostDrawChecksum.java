package com.botwithus.bot.cli.gui.scriptui;

import imgui.ImDrawData;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * A checksum of everything a host frame hands the renderer: every draw list's
 * vertex and index bytes, in draw order. Two frames with the same checksum
 * drew the same triangles.
 *
 * <p>imgui-java returns both buffers through one shared static buffer, so
 * fetching the indices overwrites the vertex bytes ({@link DrawCopy} says the
 * same). Each buffer is copied out before the next is fetched; hashing the
 * shared buffer directly made every frame hash alike.</p>
 *
 * @param crc         CRC-32 over the bytes
 * @param vertexBytes how many vertex bytes went in
 * @param indexBytes  how many index bytes went in
 */
record HostDrawChecksum(long crc, int vertexBytes, int indexBytes) {

    /** Call right after {@code ImGui.render()}, with the host's context current. */
    static HostDrawChecksum of(ImDrawData data) {
        CRC32 crc = new CRC32();
        int vertexBytes = 0;
        int indexBytes = 0;
        for (int list = 0; list < data.getCmdListsCount(); list++) {
            byte[] vertices = copyOut(data.getCmdListVtxBufferData(list));
            byte[] indices = copyOut(data.getCmdListIdxBufferData(list));
            crc.update(vertices);
            crc.update(indices);
            vertexBytes += vertices.length;
            indexBytes += indices.length;
        }
        return new HostDrawChecksum(crc.getValue(), vertexBytes, indexBytes);
    }

    private static byte[] copyOut(ByteBuffer shared) {
        byte[] bytes = new byte[shared.remaining()];
        shared.duplicate().get(bytes);
        return bytes;
    }
}
