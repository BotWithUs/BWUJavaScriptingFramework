package com.botwithus.bot.core.launcher;

import com.botwithus.msgpack.MsgArray;
import com.botwithus.msgpack.MsgBinary;
import com.botwithus.msgpack.MsgBoolean;
import com.botwithus.msgpack.MsgExtension;
import com.botwithus.msgpack.MsgFloat;
import com.botwithus.msgpack.MsgInteger;
import com.botwithus.msgpack.MsgMap;
import com.botwithus.msgpack.MsgNil;
import com.botwithus.msgpack.MsgString;
import com.botwithus.msgpack.MsgValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.HexFormat;

/**
 * Renders a decoded value as JSON for {@link FullPipeDriver}'s output, which a
 * person reads. Binary is shown as hex and an extension by its own text; a map
 * key is shown as its text whatever its type.
 */
final class MsgJson {

    private MsgJson() {
    }

    /** @return {@code value} as one line of JSON */
    static String toJson(MsgValue value) {
        return toElement(value).toString();
    }

    private static JsonElement toElement(MsgValue value) {
        return switch (value) {
            case MsgNil _ -> JsonNull.INSTANCE;
            case MsgBoolean b -> new JsonPrimitive(b.value());
            case MsgInteger i -> new JsonPrimitive(i.toBigInteger());
            case MsgFloat f -> new JsonPrimitive(f.value());
            case MsgString s -> new JsonPrimitive(s.toString());
            case MsgBinary b -> new JsonPrimitive(HexFormat.of().formatHex(b.toByteArray()));
            case MsgExtension e -> new JsonPrimitive(e.toString());
            case MsgArray a -> toArray(a);
            case MsgMap m -> toObject(m);
        };
    }

    private static JsonArray toArray(MsgArray array) {
        JsonArray out = new JsonArray();
        array.values().forEach(v -> out.add(toElement(v)));
        return out;
    }

    private static JsonObject toObject(MsgMap map) {
        JsonObject out = new JsonObject();
        for (MsgMap.Entry entry : map.entries()) {
            out.add(entry.key().stringValue().orElseGet(() -> entry.key().toString()), toElement(entry.value()));
        }
        return out;
    }
}
