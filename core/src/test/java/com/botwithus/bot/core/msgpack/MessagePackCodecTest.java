package com.botwithus.bot.core.msgpack;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MessagePackCodecTest {

    @Test
    void roundtripNull() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", null);
        byte[] encoded = MessagePackCodec.encode(map);
        Map<String, Object> decoded = MessagePackCodec.decode(encoded);
        assertNull(decoded.get("key"));
    }

    @Test
    void roundtripBoolean() {
        var map = Map.<String, Object>of("t", true, "f", false);
        var decoded = MessagePackCodec.decode(MessagePackCodec.encode(new LinkedHashMap<>(map)));
        assertEquals(true, decoded.get("t"));
        assertEquals(false, decoded.get("f"));
    }

    @Test
    void roundtripInt() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("i", 42);
        var decoded = MessagePackCodec.decode(MessagePackCodec.encode(map));
        // Small ints may decode as Integer or Long depending on msgpack internal representation
        assertEquals(42, ((Number) decoded.get("i")).intValue());
    }

    @Test
    void roundtripLong() {
        var map = Map.<String, Object>of("l", Long.MAX_VALUE);
        var decoded = MessagePackCodec.decode(MessagePackCodec.encode(new LinkedHashMap<>(map)));
        assertEquals(Long.MAX_VALUE, decoded.get("l"));
    }

    @Test
    void roundtripFloat() {
        var map = Map.<String, Object>of("f", 3.14f);
        var decoded = MessagePackCodec.decode(MessagePackCodec.encode(new LinkedHashMap<>(map)));
        // MessagePack floats are decoded as doubles
        assertInstanceOf(Double.class, decoded.get("f"));
    }

    @Test
    void roundtripDouble() {
        var map = Map.<String, Object>of("d", 2.718281828);
        var decoded = MessagePackCodec.decode(MessagePackCodec.encode(new LinkedHashMap<>(map)));
        assertEquals(2.718281828, (double) decoded.get("d"), 0.0001);
    }

    @Test
    void roundtripString() {
        var map = Map.<String, Object>of("s", "hello world");
        var decoded = MessagePackCodec.decode(MessagePackCodec.encode(new LinkedHashMap<>(map)));
        assertEquals("hello world", decoded.get("s"));
    }

    @Test
    void roundtripNestedMap() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("nested", "value");
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("inner", inner);
        var decoded = MessagePackCodec.decode(MessagePackCodec.encode(outer));
        @SuppressWarnings("unchecked")
        var decodedInner = (Map<String, Object>) decoded.get("inner");
        assertEquals("value", decodedInner.get("nested"));
    }

    @Test
    void roundtripList() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("list", List.of(1, 2, 3));
        var decoded = MessagePackCodec.decode(MessagePackCodec.encode(map));
        @SuppressWarnings("unchecked")
        var list = (List<Object>) decoded.get("list");
        assertEquals(3, list.size());
        assertEquals(1, ((Number) list.get(0)).intValue());
    }

    @Test
    void decodeCorruptDataThrows() {
        byte[] corrupt = {0x00, 0x01, 0x02, 0x03};
        // Corrupt data may throw MessagePackException or msgpack's MessageTypeCastException
        assertThrows(RuntimeException.class, () -> MessagePackCodec.decode(corrupt));
    }

    @Test
    void roundtripEmptyMap() {
        var map = new LinkedHashMap<String, Object>();
        var decoded = MessagePackCodec.decode(MessagePackCodec.encode(map));
        assertTrue(decoded.isEmpty());
    }
}
