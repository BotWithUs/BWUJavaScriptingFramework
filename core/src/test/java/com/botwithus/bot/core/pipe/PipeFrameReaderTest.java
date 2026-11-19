package com.botwithus.bot.core.pipe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PipeClient#readFrame} against the shapes a {@code PIPE_TYPE_BYTE} pipe
 * actually produces. One {@code ReadFile} is not one frame there: two pushes the
 * agent writes in one tick come back as one run of bytes, and a large reply comes
 * back in pieces. A reader that assumed one read is one frame would decode the
 * second push as garbage, which is the bug NXTLibrary PR #94 found in its own
 * tool.
 *
 * <p>The source below models {@code ReadFile} on a byte pipe: each call returns at
 * most what is left of the current write, never more than was asked for, and
 * never crosses into the next write. Each test hands it the write boundaries.</p>
 */
class PipeFrameReaderTest {

    private static final int MAX_BYTES = 1024;
    private static final byte[] FIRST = "first-push".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SECOND = "second-push".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLY = "the-reply-body".getBytes(StandardCharsets.UTF_8);
    /** Inside the 4-byte length prefix, so the header itself arrives in two pieces. */
    private static final int MID_HEADER = 2;
    /** Bytes of body that arrive with the header before the write ends. */
    private static final int BODY_BYTES_IN_FIRST_WRITE = 5;
    private static final int MID_BODY = PipeClient.FRAME_HEADER_BYTES + BODY_BYTES_IN_FIRST_WRITE;

    /** A byte pipe's ReadFile over a fixed sequence of writes. */
    private static final class WrittenPipe implements PipeClient.ByteSource {
        private final Deque<byte[]> writes = new ArrayDeque<>();
        private int offsetInHead;
        private int reads;

        WrittenPipe(List<byte[]> writes) {
            this.writes.addAll(writes);
        }

        @Override
        public int read(byte[] buf, int off, int len) {
            reads++;
            byte[] head = writes.peekFirst();
            if (head == null) {
                return -1;
            }
            int n = Math.min(len, head.length - offsetInHead);
            System.arraycopy(head, offsetInHead, buf, off, n);
            offsetInHead += n;
            if (offsetInHead == head.length) {
                writes.removeFirst();
                offsetInHead = 0;
            }
            return n;
        }
    }

    private static byte[] frame(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n = body.length;
        out.write(n);
        out.write(n >>> Byte.SIZE);
        out.write(n >>> (2 * Byte.SIZE));
        out.write(n >>> (3 * Byte.SIZE));
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] slice(byte[] bytes, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(bytes, from, out, 0, out.length);
        return out;
    }

    @Test
    @DisplayName("two frames in one write come back as two frames, in order")
    void twoFramesInOneWrite_areReadSeparately() throws IOException {
        WrittenPipe pipe = new WrittenPipe(List.of(concat(frame(FIRST), frame(SECOND))));

        assertArrayEquals(FIRST, PipeClient.readFrame(pipe, MAX_BYTES));
        assertArrayEquals(SECOND, PipeClient.readFrame(pipe, MAX_BYTES));
        assertEquals(-1, pipe.read(new byte[1], 0, 1), "nothing may be left over or invented");
    }

    @Test
    @DisplayName("a frame split across writes, mid-header and mid-body, is reassembled")
    void frameSplitAcrossWrites_isReassembled() throws IOException {
        byte[] reply = frame(REPLY);
        WrittenPipe pipe = new WrittenPipe(List.of(
                slice(reply, 0, MID_HEADER),
                slice(reply, MID_HEADER, MID_BODY),
                slice(reply, MID_BODY, reply.length)));

        assertArrayEquals(REPLY, PipeClient.readFrame(pipe, MAX_BYTES));
    }

    @Test
    @DisplayName("two whole frames and the start of a third in one write, the rest in the next")
    void framesStraddlingWrites_areAllRead() throws IOException {
        byte[] reply = frame(REPLY);
        WrittenPipe pipe = new WrittenPipe(List.of(
                concat(frame(FIRST), frame(SECOND), slice(reply, 0, MID_BODY)),
                slice(reply, MID_BODY, reply.length)));

        assertArrayEquals(FIRST, PipeClient.readFrame(pipe, MAX_BYTES));
        assertArrayEquals(SECOND, PipeClient.readFrame(pipe, MAX_BYTES));
        assertArrayEquals(REPLY, PipeClient.readFrame(pipe, MAX_BYTES));
    }

    @Test
    @DisplayName("a pipe that hands back one byte per read still yields whole frames")
    void oneBytePerRead_yieldsWholeFrames() throws IOException {
        byte[] stream = concat(frame(FIRST), frame(SECOND));
        List<byte[]> bytes = new ArrayList<>();
        for (byte b : stream) {
            bytes.add(new byte[] {b});
        }
        WrittenPipe pipe = new WrittenPipe(bytes);

        assertArrayEquals(FIRST, PipeClient.readFrame(pipe, MAX_BYTES));
        assertArrayEquals(SECOND, PipeClient.readFrame(pipe, MAX_BYTES));
        assertEquals(stream.length, pipe.reads, "one read per byte: the reader looped rather than assumed");
    }

    @Test
    @DisplayName("a pipe closing mid-frame is an error, not a short frame")
    void closeMidFrame_throws() {
        byte[] reply = frame(REPLY);
        WrittenPipe pipe = new WrittenPipe(List.of(slice(reply, 0, MID_BODY)));

        assertThrows(IOException.class, () -> PipeClient.readFrame(pipe, MAX_BYTES));
    }

    @Test
    @DisplayName("a length past the cap is refused before any body is read")
    void oversizeLength_isRefused() {
        WrittenPipe pipe = new WrittenPipe(List.of(frame(new byte[MAX_BYTES + 1])));

        assertThrows(PipeException.class, () -> PipeClient.readFrame(pipe, MAX_BYTES));
    }
}
