package com.botwithus.bot.core.launcher;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePackException;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * One decoded frame body (ADR 2.4). What each field means depends on
 * {@link #kind()}: a response is matched on {@link #id()} and judged by
 * {@link #isOk()}; an event is named by {@link #method()} and numbered by
 * {@link #seq()}.
 *
 * @param kind   request, response or event
 * @param id     the request id; 0 for an event
 * @param method the method or event name
 * @param isOk   a response's {@code ok}
 * @param body   the body map; an empty map when absent
 * @param error  a failed response's error
 * @param seq    an event's sequence number; 0 otherwise
 */
record Envelope(Kind kind, long id, String method, boolean isOk, Value body, Optional<WireError> error,
                long seq) {

    /** The envelope's {@code kind}. */
    enum Kind { REQUEST, RESPONSE, EVENT, UNKNOWN }

    Envelope {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(error, "error");
    }

    /**
     * Decodes one frame body.
     *
     * @throws MalformedFrameException if it is not exactly one map with
     *                                 {@code v = 1}, or does not decode
     */
    static Envelope decode(byte[] frame) {
        Value root = decodeSingleValue(frame);
        if (!root.isMapValue()) {
            throw new MalformedFrameException("the frame body is not a map");
        }
        long version = WireValues.integer(root, "v", -1L);
        if (version != LauncherProtocol.ENVELOPE_VERSION) {
            throw new MalformedFrameException("unsupported envelope version " + version);
        }
        Kind kind = kindOf(WireValues.string(root, "kind", ""));
        Optional<WireError> error = WireValues.field(root, "error").filter(Value::isMapValue).map(WireError::decode);
        return new Envelope(kind, WireValues.integer(root, "id", 0L), WireValues.string(root, "method", ""),
                WireValues.bool(root, "ok", false), WireValues.map(root, "body"), error,
                WireValues.integer(root, "seq", 0L));
    }

    /** @return the single msgpack value {@code frame} holds, with nothing after it */
    static Value decodeSingleValue(byte[] frame) {
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(frame)) {
            Value value = unpacker.unpackValue();
            if (unpacker.hasNext()) {
                throw new MalformedFrameException("trailing bytes after the frame body");
            }
            return value;
        } catch (IOException | MessagePackException e) {
            throw new MalformedFrameException("the frame body does not decode", e);
        }
    }

    private static Kind kindOf(String wire) {
        return switch (wire) {
            case "req" -> Kind.REQUEST;
            case "resp" -> Kind.RESPONSE;
            case "event" -> Kind.EVENT;
            default -> Kind.UNKNOWN;
        };
    }

    /** @return an empty map, the body of a request with no parameters */
    static Value emptyBody() {
        return ValueFactory.emptyMap();
    }
}
