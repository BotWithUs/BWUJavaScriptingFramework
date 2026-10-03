package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.StopMode;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.Value;
import org.msgpack.value.ValueFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Builds the requests this host sends, in canonical form (ADR 2.5): keys in the
 * order of the ADR's schema tables, the smallest encoding for every integer and
 * string, and an absent optional field omitted rather than sent as nil. The
 * golden fixtures hold each builder to the exact bytes.
 */
final class LauncherRequests {

    private LauncherRequests() {
    }

    /** @return one frame body: the envelope of a request carrying {@code body} */
    static byte[] encode(long id, String method, Value body) {
        Value envelope = ValueFactory.newMapBuilder()
                .put(key("v"), ValueFactory.newInteger(LauncherProtocol.ENVELOPE_VERSION))
                .put(key("id"), ValueFactory.newInteger(id))
                .put(key("kind"), ValueFactory.newString("req"))
                .put(key("method"), ValueFactory.newString(method))
                .put(key("body"), body)
                .build();
        return pack(envelope);
    }

    /** @return {@code value} packed as msgpack-core packs it */
    static byte[] pack(Value value) {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packer.packValue(value);
            return packer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("packing to memory failed", e);
        }
    }

    /** @return {@code hello}'s body for this host (ADR 2.7, amendment A5) */
    static Value hello(HelloParams params) {
        var builder = ValueFactory.newMapBuilder()
                .put(key("protocolMin"), ValueFactory.newInteger(LauncherProtocol.PROTOCOL_VERSION))
                .put(key("protocolMax"), ValueFactory.newInteger(LauncherProtocol.PROTOCOL_VERSION))
                .put(key("clientKind"), ValueFactory.newString(LauncherProtocol.CLIENT_KIND_HOST))
                .put(key("clientVersion"), ValueFactory.newString(params.clientVersion()))
                .put(key("hostKind"), ValueFactory.newString(LauncherProtocol.HOST_KIND_JAVA))
                .put(key("hostPid"), ValueFactory.newInteger(params.hostPid()));
        params.label().ifPresent(label -> builder.put(key("hostLabel"), ValueFactory.newString(label)));
        return builder.build();
    }

    /** @return {@code client.launch}'s body */
    static Value clientLaunch(String accountId, int characterIndex) {
        return ValueFactory.newMapBuilder()
                .put(key("accountId"), ValueFactory.newString(accountId))
                .put(key("characterIndex"), ValueFactory.newInteger(characterIndex))
                .build();
    }

    /** @return {@code client.stop}'s body */
    static Value clientStop(String clientId, StopMode mode) {
        return ValueFactory.newMapBuilder()
                .put(key("clientId"), ValueFactory.newString(clientId))
                .put(key("mode"), ValueFactory.newString(stopModeWire(mode)))
                .build();
    }

    /** @return {@code client.status}'s body */
    static Value clientStatus(String clientId) {
        return ValueFactory.newMapBuilder()
                .put(key("clientId"), ValueFactory.newString(clientId))
                .build();
    }

    /** @return {@code events.subscribe}'s body */
    static Value subscribe(List<String> topics) {
        List<Value> names = topics.stream().<Value>map(ValueFactory::newString).toList();
        return ValueFactory.newMapBuilder()
                .put(key("topics"), ValueFactory.newArray(names))
                .build();
    }

    /** @return {@code host.ack_close}'s body */
    static Value ackClose(long requestId, CloseDecision decision) {
        return ValueFactory.newMapBuilder()
                .put(key("requestId"), ValueFactory.newInteger(requestId))
                .put(key("decision"), ValueFactory.newString(decision.wire()))
                .build();
    }

    /** @return the wire spelling of {@code mode}: {@code "close"} or {@code "kill"} */
    static String stopModeWire(StopMode mode) {
        return switch (mode) {
            case GRACEFUL -> "close";
            case KILL -> "kill";
        };
    }

    private static Value key(String name) {
        return ValueFactory.newString(name);
    }
}
