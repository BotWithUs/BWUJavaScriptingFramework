package com.botwithus.bot.core.msgpack;

import com.botwithus.msgpack.MsgPacker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    private static byte[] nestedMaps(int depth) {
        MsgPacker packer = new MsgPacker();
        for (int i = 0; i < depth; i++) {
            packer.packMapHeader(1);
            packer.packString("a");
        }
        packer.packNil();
        return packer.toByteArray();
    }

    @Test
    @DisplayName("a shallow map still decodes normally")
    void decodesShallowInput() {
        Map<String, Object> decoded = MessagePackCodec.decode(nestedMaps(3));
        assertInstanceOf(Map.class, decoded.get("a"), "nesting within the bound survives");
    }

    @Test
    @DisplayName("nesting past the depth bound is rejected as a codec error")
    void rejectsInputPastDepthBound() {
        byte[] deep = nestedMaps(200);
        MessagePackException e = assertThrows(MessagePackException.class,
                () -> MessagePackCodec.decode(deep));
        assertEquals(true, e.getMessage().contains("nesting"),
                "expected a nesting-bound message, got: " + e.getMessage());
    }

    @Test
    @DisplayName("nesting deep enough to overflow a naive recursive decode surfaces as MessagePackException")
    void deeplyNestedInputDoesNotEscapeAsError() {
        // The decode streams and checks the bound before each descent, so this
        // never recurses past the limit. A StackOverflowError would be an Error,
        // escape every catch and kill the reader thread; assert it never gets
        // that far.
        byte[] veryDeep = nestedMaps(200_000);
        assertThrows(MessagePackException.class, () -> MessagePackCodec.decode(veryDeep));
    }
}
