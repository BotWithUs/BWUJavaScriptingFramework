package com.botwithus.bot.core.msgpack;

import org.msgpack.core.*;
import org.msgpack.value.*;

import java.io.IOException;
import java.util.*;

/**
 * Encodes Map to MessagePack bytes and decodes MessagePack bytes to Map.
 */
public final class MessagePackCodec {

    private MessagePackCodec() {}

    public static byte[] encode(Map<String, Object> map) {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packMap(packer, map);
            return packer.toByteArray();
        } catch (IOException e) {
            throw new MessagePackException("MessagePack encode failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> decode(byte[] data) {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(data)) {
            Value value = unpacker.unpackValue();
            return valueToMap(value.asMapValue());
        } catch (Exception e) {
            throw new MessagePackException("MessagePack decode failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void packMap(MessagePacker packer, Map<String, Object> map) throws IOException {
        packer.packMapHeader(map.size());
        for (var entry : map.entrySet()) {
            packer.packString(entry.getKey());
            packValue(packer, entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private static void packValue(MessagePacker packer, Object value) throws IOException {
        switch (value) {
            case null -> packer.packNil();
            case String s -> packer.packString(s);
            case Integer i -> packer.packInt(i);
            case Long l -> packer.packLong(l);
            case Float f -> packer.packFloat(f);
            case Double d -> packer.packDouble(d);
            case Boolean b -> packer.packBoolean(b);
            case Map<?, ?> m -> packMap(packer, (Map<String, Object>) m);
            case List<?> list -> {
                packer.packArrayHeader(list.size());
                for (Object item : list) {
                    packValue(packer, item);
                }
            }
            default -> packer.packString(value.toString());
        }
    }

    private static Map<String, Object> valueToMap(MapValue mapValue) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : mapValue.entrySet()) {
            String key = entry.getKey().asStringValue().asString();
            result.put(key, valueToObject(entry.getValue()));
        }
        return result;
    }

    private static Object valueToObject(Value value) {
        return switch (value.getValueType()) {
            case NIL -> null;
            case BOOLEAN -> value.asBooleanValue().getBoolean();
            case INTEGER -> {
                IntegerValue iv = value.asIntegerValue();
                yield iv.isInIntRange() ? iv.asInt() : iv.asLong();
            }
            case FLOAT -> value.asFloatValue().toDouble();
            case STRING -> value.asStringValue().asString();
            case ARRAY -> {
                ArrayValue arr = value.asArrayValue();
                List<Object> list = new ArrayList<>(arr.size());
                for (Value item : arr) {
                    list.add(valueToObject(item));
                }
                yield list;
            }
            case MAP -> valueToMap(value.asMapValue());
            case BINARY -> value.asBinaryValue().asByteArray();
            case EXTENSION -> value.toString();
        };
    }
}
