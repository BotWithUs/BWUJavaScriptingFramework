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

    @Test
    void loginToLobbyMatchesPython() {
        // Exact message RpcClient.doCall builds for login_to_lobby
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", "login_to_lobby");
        request.put("id", 1);
        // params is empty, so not added (same as Python)

        byte[] bytes = MessagePackCodec.encode(request);

        // Python: msgpack.packb({"method": "login_to_lobby", "id": 1}, use_bin_type=True)
        // produces: 82 a6 6d 65 74 68 6f 64 ae 6c 6f 67 69 6e 5f 74 6f 5f 6c 6f 62 62 79 a2 69 64 01
        byte[] expected = {
            (byte)0x82,                                                     // fixmap(2)
            (byte)0xa6, 0x6d, 0x65, 0x74, 0x68, 0x6f, 0x64,              // fixstr "method"
            (byte)0xae, 0x6c, 0x6f, 0x67, 0x69, 0x6e, 0x5f, 0x74, 0x6f, // fixstr "login_to_lobby"
            0x5f, 0x6c, 0x6f, 0x62, 0x62, 0x79,
            (byte)0xa2, 0x69, 0x64,                                        // fixstr "id"
            0x01                                                            // fixint 1
        };

        StringBuilder javaSb = new StringBuilder();
        for (byte b : bytes) javaSb.append(String.format("%02x ", b & 0xff));
        StringBuilder pySb = new StringBuilder();
        for (byte b : expected) pySb.append(String.format("%02x ", b & 0xff));

        assertEquals(pySb.toString().trim(), javaSb.toString().trim(),
            "Java msgpack encoding must match Python msgpack encoding");
    }
}
