package com.botwithus.bot.core.msgpack;

import com.botwithus.msgpack.MsgPacker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the decoded shape and the failure modes callers depend on. Each case
 * below is behaviour the codec had over msgpack-core and keeps, except the
 * oversize-length cases, which used to escape as an OutOfMemoryError or a
 * NegativeArraySizeException and are now a {@link MessagePackException}.
 */
class MessagePackCodecContractTest {

    // MessagePack format codes, from the spec.
    private static final byte FIXMAP_1 = (byte) 0x81;
    private static final byte FIXSTR_1 = (byte) 0xa1;
    private static final byte UINT64 = (byte) 0xcf;
    private static final byte STR32 = (byte) 0xdb;
    private static final byte ARRAY32 = (byte) 0xdd;
    private static final byte MAP32 = (byte) 0xdf;
    private static final byte FIXEXT1 = (byte) 0xd4;
    private static final byte FIXEXT2 = (byte) 0xd5;
    private static final byte FIXEXT4 = (byte) 0xd6;
    private static final byte EXT8 = (byte) 0xc7;
    private static final byte NEVER_USED = (byte) 0xc1;
    private static final byte FF = (byte) 0xff;
    private static final byte TIMESTAMP_TYPE = -1;
    private static final byte KEY_I = 'i';

    private static final String NESTING_MESSAGE = "MessagePack nesting exceeds 64 levels";
    /** The deepest chain of maps whose innermost value still decodes: a map costs two levels. */
    private static final int DEEPEST_MAP_CHAIN = 32;
    /** The deepest chain of arrays whose innermost value still decodes: an element costs one. */
    private static final int DEEPEST_ARRAY_CHAIN = 63;

    /** {@code {"i": <valueBytes>}}. */
    private static byte[] underKeyI(byte... valueBytes) {
        byte[] frame = new byte[valueBytes.length + 3];
        frame[0] = FIXMAP_1;
        frame[1] = FIXSTR_1;
        frame[2] = KEY_I;
        System.arraycopy(valueBytes, 0, frame, 3, valueBytes.length);
        return frame;
    }

    private static Object decodeI(byte... valueBytes) {
        return MessagePackCodec.decode(underKeyI(valueBytes)).get("i");
    }

    private static byte[] nestedMaps(int depth) {
        MsgPacker packer = new MsgPacker();
        for (int i = 0; i < depth; i++) {
            packer.packMapHeader(1).packString("a");
        }
        return packer.packNil().toByteArray();
    }

    private static byte[] nestedArraysUnderKey(int depth) {
        MsgPacker packer = new MsgPacker().packMapHeader(1).packString("a");
        for (int i = 0; i < depth; i++) {
            packer.packArrayHeader(1);
        }
        return packer.packNil().toByteArray();
    }

    @Test
    @DisplayName("every integer decodes as a Long, in the int range included")
    void integers_anySize_decodeAsLong() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("small", 1);
        source.put("negative", -1);
        source.put("big", Long.MIN_VALUE);
        source.put("list", List.of(2));
        Map<String, Object> decoded = MessagePackCodec.decode(MessagePackCodec.encode(source));
        assertEquals(1L, assertInstanceOf(Long.class, decoded.get("small")));
        assertEquals(-1L, assertInstanceOf(Long.class, decoded.get("negative")));
        assertEquals(Long.MIN_VALUE, assertInstanceOf(Long.class, decoded.get("big")));
        List<?> list = assertInstanceOf(List.class, decoded.get("list"));
        assertEquals(2L, assertInstanceOf(Long.class, list.get(0)));
    }

    @Test
    @DisplayName("a uint64 above Long.MAX_VALUE is refused")
    void uint64_aboveLongMax_throws() {
        assertThrows(MessagePackException.class,
                () -> decodeI(UINT64, FF, FF, FF, FF, FF, FF, FF, FF));
    }

    @Test
    @DisplayName("a string that is not valid UTF-8 is refused")
    void string_invalidUtf8_throws() {
        assertThrows(MessagePackException.class, () -> decodeI(FIXSTR_1, FF));
    }

    @Test
    @DisplayName("an extension decodes as (type,0x...) with each signed byte in hex")
    void extension_nonTimestamp_decodesAsMsgpackJavaString() {
        assertEquals("(5,0x-1)", decodeI(FIXEXT1, (byte) 5, FF));
        assertEquals("(1,0x1a)", decodeI(FIXEXT2, (byte) 1, (byte) 0x01, (byte) 0x0a));
    }

    @Test
    @DisplayName("a timestamp extension decodes as Instant.toString()")
    void extension_timestamp_decodesAsInstant() {
        assertEquals("1970-01-01T00:00:01Z",
                decodeI(FIXEXT4, TIMESTAMP_TYPE, (byte) 0, (byte) 0, (byte) 0, (byte) 1));
    }

    @Test
    @DisplayName("a timestamp of any length but 4, 8 or 12 is refused")
    void extension_timestampOfOddLength_throws() {
        assertThrows(MessagePackException.class,
                () -> decodeI(EXT8, (byte) 3, TIMESTAMP_TYPE, (byte) 0, (byte) 0, (byte) 0));
    }

    @Test
    @DisplayName("a non-string map key is refused on decode")
    void decode_integerKey_throws() {
        byte[] frame = {FIXMAP_1, (byte) 1, (byte) 1};
        assertThrows(MessagePackException.class, () -> MessagePackCodec.decode(frame));
    }

    @Test
    @DisplayName("a null key throws NullPointerException, unwrapped")
    void encode_nullKey_throwsNpe() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(null, 1);
        assertThrows(NullPointerException.class, () -> MessagePackCodec.encode(map));
    }

    @Test
    @DisplayName("a nested non-string key throws ClassCastException, unwrapped")
    void encode_nestedIntegerKey_throwsClassCast() {
        Map<Object, Object> inner = new LinkedHashMap<>();
        inner.put(1, "x");
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("inner", inner);
        assertThrows(ClassCastException.class, () -> MessagePackCodec.encode(outer));
    }

    @Test
    @DisplayName("a nested map costs two levels: 32 deep decodes, 33 is refused")
    void depth_mapChain_boundaryAt32() {
        assertTrue(MessagePackCodec.decode(nestedMaps(DEEPEST_MAP_CHAIN)).containsKey("a"));
        MessagePackException e = assertThrows(MessagePackException.class,
                () -> MessagePackCodec.decode(nestedMaps(DEEPEST_MAP_CHAIN + 1)));
        assertEquals(NESTING_MESSAGE, e.getMessage());
    }

    @Test
    @DisplayName("an array element costs one level: 63 deep decodes, 64 is refused")
    void depth_arrayChain_boundaryAt63() {
        assertTrue(MessagePackCodec.decode(nestedArraysUnderKey(DEEPEST_ARRAY_CHAIN)).containsKey("a"));
        MessagePackException e = assertThrows(MessagePackException.class,
                () -> MessagePackCodec.decode(nestedArraysUnderKey(DEEPEST_ARRAY_CHAIN + 1)));
        assertEquals(NESTING_MESSAGE, e.getMessage());
    }

    @Test
    @DisplayName("bytes after the root map are ignored")
    void decode_trailingBytes_ignored() {
        byte[] frame = {FIXMAP_1, FIXSTR_1, KEY_I, (byte) 7, NEVER_USED, FF};
        assertEquals(Map.of("i", 7L), MessagePackCodec.decode(frame));
    }

    @Test
    @DisplayName("a root that is not a map is refused")
    void decode_arrayRoot_throws() {
        byte[] frame = new MsgPacker().packArrayHeader(0).toByteArray();
        assertThrows(MessagePackException.class, () -> MessagePackCodec.decode(frame));
    }

    @Test
    @DisplayName("null input is a MessagePackException")
    void decode_null_throws() {
        assertThrows(MessagePackException.class, () -> MessagePackCodec.decode(null));
    }

    @Test
    @DisplayName("a string length past the end is refused before allocating")
    void decode_oversizeStringLength_throws() {
        assertThrows(MessagePackException.class, () -> decodeI(STR32, (byte) 0x7f, FF, FF, FF));
    }

    @Test
    @DisplayName("an array count past the end is refused before allocating")
    void decode_oversizeArrayCount_throws() {
        assertThrows(MessagePackException.class, () -> decodeI(ARRAY32, FF, FF, FF, FF));
    }

    @Test
    @DisplayName("a root map count past the end is refused before allocating")
    void decode_oversizeRootMapCount_throws() {
        byte[] frame = {MAP32, (byte) 0x7f, FF, FF, FF};
        assertThrows(MessagePackException.class, () -> MessagePackCodec.decode(frame));
    }

    @Test
    @DisplayName("binary decodes as byte[]")
    void binary_decodesAsByteArray() {
        Map<String, Object> decoded = MessagePackCodec.decode(
                new MsgPacker().packMapHeader(1).packString("b").packBinary(new byte[] {1, 2}).toByteArray());
        assertArrayEquals(new byte[] {1, 2}, assertInstanceOf(byte[].class, decoded.get("b")));
    }
}
