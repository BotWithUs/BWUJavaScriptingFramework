package com.botwithus.bot.core.msgpack;

import com.botwithus.msgpack.MsgExtensionHeader;
import com.botwithus.msgpack.MsgPackException;
import com.botwithus.msgpack.MsgPacker;
import com.botwithus.msgpack.MsgTimestamps;
import com.botwithus.msgpack.MsgType;
import com.botwithus.msgpack.MsgUnpacker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes a {@code Map} to MessagePack bytes and decodes MessagePack bytes to a
 * {@code Map}, over {@code com.botwithus.msgpack}.
 *
 * <p>The decoded shape is a contract callers already cast against:
 * <ul>
 *   <li>the root must be a map, its keys must be strings, and bytes after it are ignored;</li>
 *   <li>every integer decodes to a {@code Long}, whatever its size; a uint64 above
 *       {@code Long.MAX_VALUE} is refused;</li>
 *   <li>every float decodes to a {@code Double}, a string to a {@code String}, a binary to
 *       a {@code byte[]}, an array to a {@code List} and a map to a {@code LinkedHashMap}
 *       in wire order;</li>
 *   <li>an extension decodes to a {@code String}: a timestamp (type {@code -1}) as
 *       {@code Instant.toString()}, anything else as {@code (type,0x...)}.</li>
 * </ul>
 * Every malformed input, a length larger than the bytes left included, is a
 * {@link MessagePackException}.
 */
public final class MessagePackCodec {

    /**
     * Maximum container nesting accepted when decoding. The producer's RPC
     * replies are shallow — the deepest legitimate shape is a map holding a
     * list of flat maps (the interface tree is a breadth-first list with parent
     * indices, not a nested tree), so real traffic sits at depth 3. This bound
     * exists only to stop a hostile or malfunctioning producer sending a
     * deeply-nested document that exhausts the stack in the recursive decode.
     * A nested map costs two levels and an array element one; that count is
     * older than this class and is kept so the limit means what it always has.
     */
    private static final int MAX_DEPTH = 64;

    /** {@code Integer.toString(b, 16)} per signed byte, as msgpack-java spelled an extension. */
    private static final int HEX_RADIX = 16;

    private MessagePackCodec() {
    }

    /**
     * @return {@code map} packed with the smallest encoding of each part
     * @throws NullPointerException if a map key is {@code null}
     * @throws ClassCastException if a nested map has a key that is not a {@code String}
     */
    public static byte[] encode(Map<String, Object> map) {
        MsgPacker packer = new MsgPacker();
        packMap(packer, map);
        return packer.toByteArray();
    }

    /**
     * @return the map {@code data} starts with
     * @throws MessagePackException if {@code data} is not a well-formed map, as described above
     */
    public static Map<String, Object> decode(byte[] data) {
        try {
            MsgUnpacker unpacker = new MsgUnpacker(data);
            if (unpacker.nextType() != MsgType.MAP) {
                throw new MsgPackException("the root value is a " + unpacker.nextType() + ", not a map");
            }
            return readMapBody(unpacker, unpacker.unpackMapHeader(), 0);
        } catch (MessagePackException e) {
            // Already this codec's own error (the depth bound); don't bury it
            // under a second, less specific wrapper.
            throw e;
        } catch (RuntimeException e) {
            // MsgPackException for bad input; anything else (a null array) is
            // still a frame the caller cannot use, and is reported the same way.
            throw new MessagePackException("MessagePack decode failed", e);
        }
    }

    private static void packMap(MsgPacker packer, Map<?, ?> map) {
        packer.packMapHeader(map.size());
        for (var entry : map.entrySet()) {
            packer.packString(requireStringKey(entry.getKey()));
            packValue(packer, entry.getValue());
        }
    }

    /** The codec has always failed a bad key with these two exception types, unwrapped. */
    private static String requireStringKey(Object key) {
        return switch (key) {
            case String s -> s;
            case null -> throw new NullPointerException("a map key is null");
            default -> throw new ClassCastException("a map key is a " + key.getClass().getName()
                    + ", not a String");
        };
    }

    private static void packValue(MsgPacker packer, Object value) {
        switch (value) {
            case null -> packer.packNil();
            case String s -> packer.packString(s);
            case Integer i -> packer.packInt(i);
            case Long l -> packer.packLong(l);
            case Float f -> packer.packFloat(f);
            case Double d -> packer.packDouble(d);
            case Boolean b -> packer.packBoolean(b);
            case Map<?, ?> m -> packMap(packer, m);
            case List<?> list -> {
                packer.packArrayHeader(list.size());
                for (Object item : list) {
                    packValue(packer, item);
                }
            }
            default -> packer.packString(value.toString());
        }
    }

    private static Map<String, Object> readMapBody(MsgUnpacker unpacker, int count, int depth) {
        requireDepth(depth);
        Map<String, Object> result = LinkedHashMap.newLinkedHashMap(count);
        for (int i = 0; i < count; i++) {
            if (unpacker.nextType() != MsgType.STRING) {
                throw new MsgPackException("a map key is a " + unpacker.nextType() + ", not a string");
            }
            String key = unpacker.unpackString();
            result.put(key, readValue(unpacker, depth + 1));
        }
        return result;
    }

    private static Object readValue(MsgUnpacker unpacker, int depth) {
        requireDepth(depth);
        return switch (unpacker.nextType()) {
            case NIL -> {
                unpacker.unpackNil();
                yield null;
            }
            case BOOLEAN -> unpacker.unpackBoolean();
            case INTEGER -> unpacker.unpackLong();
            case FLOAT -> unpacker.unpackDouble();
            case STRING -> unpacker.unpackString();
            case BINARY -> unpacker.unpackBinary();
            case EXTENSION -> extensionToString(unpacker);
            case ARRAY -> readArray(unpacker, depth);
            case MAP -> readMapBody(unpacker, unpacker.unpackMapHeader(), depth + 1);
        };
    }

    private static List<Object> readArray(MsgUnpacker unpacker, int depth) {
        int count = unpacker.unpackArrayHeader();
        List<Object> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(readValue(unpacker, depth + 1));
        }
        return list;
    }

    private static String extensionToString(MsgUnpacker unpacker) {
        MsgExtensionHeader header = unpacker.unpackExtensionHeader();
        byte[] payload = unpacker.readPayload(header.length());
        if (header.extType() == MsgTimestamps.TIMESTAMP_TYPE) {
            return MsgTimestamps.decodePayload(payload).toString();
        }
        StringBuilder text = new StringBuilder().append('(').append(header.extType()).append(",0x");
        for (byte b : payload) {
            text.append(Integer.toString(b, HEX_RADIX));
        }
        return text.append(')').toString();
    }

    private static void requireDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new MessagePackException("MessagePack nesting exceeds " + MAX_DEPTH + " levels");
        }
    }
}
