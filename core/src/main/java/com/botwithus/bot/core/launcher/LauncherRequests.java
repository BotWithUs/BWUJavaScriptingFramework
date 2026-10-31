package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.StopMode;
import com.botwithus.msgpack.MsgMap;
import com.botwithus.msgpack.MsgPack;
import com.botwithus.msgpack.MsgValue;

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
    static byte[] encode(long id, String method, MsgValue body) {
        MsgValue envelope = MsgMap.builder()
                .put(key("v"), MsgValue.of(LauncherProtocol.ENVELOPE_VERSION))
                .put(key("id"), MsgValue.of(id))
                .put(key("kind"), MsgValue.of("req"))
                .put(key("method"), MsgValue.of(method))
                .put(key("body"), body)
                .build();
        return pack(envelope);
    }

    /** @return {@code value} packed with the smallest encoding of each part */
    static byte[] pack(MsgValue value) {
        return MsgPack.encode(value);
    }

    /** @return {@code hello}'s body for this host (ADR 2.7, amendment A5) */
    static MsgValue hello(HelloParams params) {
        var builder = MsgMap.builder()
                .put(key("protocolMin"), MsgValue.of(LauncherProtocol.PROTOCOL_VERSION))
                .put(key("protocolMax"), MsgValue.of(LauncherProtocol.PROTOCOL_VERSION))
                .put(key("clientKind"), MsgValue.of(LauncherProtocol.CLIENT_KIND_HOST))
                .put(key("clientVersion"), MsgValue.of(params.clientVersion()))
                .put(key("hostKind"), MsgValue.of(LauncherProtocol.HOST_KIND_JAVA))
                .put(key("hostPid"), MsgValue.of(params.hostPid()));
        params.label().ifPresent(label -> builder.put(key("hostLabel"), MsgValue.of(label)));
        return builder.build();
    }

    /** @return {@code client.launch}'s body */
    static MsgValue clientLaunch(String accountId, int characterIndex) {
        return MsgMap.builder()
                .put(key("accountId"), MsgValue.of(accountId))
                .put(key("characterIndex"), MsgValue.of(characterIndex))
                .build();
    }

    /** @return {@code client.stop}'s body */
    static MsgValue clientStop(String clientId, StopMode mode) {
        return MsgMap.builder()
                .put(key("clientId"), MsgValue.of(clientId))
                .put(key("mode"), MsgValue.of(stopModeWire(mode)))
                .build();
    }

    /** @return {@code client.status}'s body */
    static MsgValue clientStatus(String clientId) {
        return MsgMap.builder()
                .put(key("clientId"), MsgValue.of(clientId))
                .build();
    }

    /** @return {@code events.subscribe}'s body */
    static MsgValue subscribe(List<String> topics) {
        List<MsgValue> names = topics.stream().map(MsgValue::of).toList();
        return MsgMap.builder()
                .put(key("topics"), MsgValue.of(names))
                .build();
    }

    /** @return {@code host.ack_close}'s body */
    static MsgValue ackClose(long requestId, CloseDecision decision) {
        return MsgMap.builder()
                .put(key("requestId"), MsgValue.of(requestId))
                .put(key("decision"), MsgValue.of(decision.wire()))
                .build();
    }

    /**
     * @return {@code sdn.bundle}'s body: the key to seal to, the catalogue ids wanted,
     *         and a request for a partial delivery, so one script that fails does not
     *         cost the rest; a launcher that predates it ignores the flag
     */
    static MsgValue sdnBundle(byte[] publicKey, List<String> scriptIds) {
        List<MsgValue> ids = scriptIds.stream().map(MsgValue::of).toList();
        return MsgMap.builder()
                .put(key("publicKey"), MsgValue.of(publicKey))
                .put(key("scriptIds"), MsgValue.of(ids))
                .put(key(SdnWire.ALLOW_PARTIAL), MsgValue.of(true))
                .build();
    }

    /** @return {@code sdn.bundle_chunk}'s body */
    static MsgValue sdnBundleChunk(long deliveryId, long index) {
        return MsgMap.builder()
                .put(key("deliveryId"), MsgValue.of(deliveryId))
                .put(key("index"), MsgValue.of(index))
                .build();
    }

    /** @return the wire spelling of {@code mode}: {@code "close"} or {@code "kill"} */
    static String stopModeWire(StopMode mode) {
        return switch (mode) {
            case GRACEFUL -> "close";
            case KILL -> "kill";
        };
    }

    private static MsgValue key(String name) {
        return MsgValue.of(name);
    }
}
