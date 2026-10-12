package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.LauncherException;
import org.msgpack.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks a successful {@code hello} reply (ADR 2.7). The predicate is the
 * negotiated {@code protocolVersion} and the {@code surface}; the version
 * strings and connection id are logged and nothing else. The method list is
 * returned, because a method added after protocol 1 shipped is how a host tells
 * a newer launcher from an older one (amendment B1).
 */
final class HelloReply {

    /** The client-local code for a reply this host cannot work with. */
    static final String PROTOCOL_MISMATCH = "protocol_mismatch";

    private static final Logger log = LoggerFactory.getLogger(HelloReply.class);

    private HelloReply() {
    }

    /**
     * @return the methods the service offers this connection
     * @throws LauncherException if the service negotiated something other than protocol 1 on the automation surface
     */
    static Set<String> check(Value body) {
        long version = WireValues.integer(body, "protocolVersion", -1L);
        String surface = WireValues.string(body, "surface", "");
        if (version != LauncherProtocol.PROTOCOL_VERSION || !surface.equals(LauncherProtocol.SURFACE_AUTOMATION)) {
            throw new LauncherException(PROTOCOL_MISMATCH, "hello negotiated protocol " + version
                    + " on surface '" + surface + "'; this host speaks protocol "
                    + LauncherProtocol.PROTOCOL_VERSION + " on the automation surface");
        }
        Set<String> methods = WireValues.array(body, "methods").stream().filter(Value::isStringValue)
                .map(m -> m.asStringValue().asString()).collect(Collectors.toUnmodifiableSet());
        log.info("Launcher service {} accepted this host (connection {}, {} methods)",
                WireValues.string(body, "serviceVersion", "?"), WireValues.integer(body, "connectionId", 0L),
                methods.size());
        return methods;
    }
}
