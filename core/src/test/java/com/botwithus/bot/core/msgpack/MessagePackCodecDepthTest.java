package com.botwithus.bot.core.msgpack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Decode must reject pathologically nested input as a bad frame rather than
 * letting it take down the RPC reader thread.
 */
class MessagePackCodecDepthTest {

    /** {@code {"a": {"a": ... nil}}} nested {@code depth} levels deep. */
    private static byte[] nestedMaps(int depth) throws IOException {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            for (int i = 0; i < depth; i++) {
                packer.packMapHeader(1);
                packer.packString("a");
            }
            packer.packNil();
            return packer.toByteArray();
        }
    }

    @Test
    @DisplayName("a shallow map still decodes normally")
    void decodesShallowInput() throws IOException {
        Map<String, Object> decoded = MessagePackCodec.decode(nestedMaps(3));
        assertInstanceOf(Map.class, decoded.get("a"), "nesting within the bound survives");
    }

    @Test
    @DisplayName("nesting past the depth bound is rejected as a codec error")
    void rejectsInputPastDepthBound() throws IOException {
        byte[] deep = nestedMaps(200);
        MessagePackException e = assertThrows(MessagePackException.class,
                () -> MessagePackCodec.decode(deep));
        assertEquals(true, e.getMessage().contains("nesting"),
                "expected a nesting-bound message, got: " + e.getMessage());
    }

    @Test
    @DisplayName("nesting deep enough to overflow the stack surfaces as MessagePackException")
    void deeplyNestedInputDoesNotEscapeAsError() throws IOException {
        // msgpack-core builds the Value tree recursively inside unpackValue(),
        // before our own depth-bounded walk runs — so this overflows there, not
        // in valueToMap. StackOverflowError is an Error and would escape a
        // catch(Exception) and kill the reader thread; assert it is converted.
        byte[] veryDeep = nestedMaps(200_000);
        assertThrows(MessagePackException.class, () -> MessagePackCodec.decode(veryDeep));
    }
}
