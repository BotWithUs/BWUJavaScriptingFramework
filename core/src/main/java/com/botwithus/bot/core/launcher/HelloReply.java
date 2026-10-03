package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.LauncherException;
import org.msgpack.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks a successful {@code hello} reply (ADR 2.7). The predicate is the
 * negotiated {@code protocolVersion} and the {@code surface}; the version
 * strings, connection id and method list are logged and nothing else.
 */
final class HelloReply {

    /** The client-local code for a reply this host cannot work with. */
    static final String PROTOCOL_MISMATCH = "protocol_mismatch";

    private static final Logger log = LoggerFactory.getLogger(HelloReply.class);

    private HelloReply() {
    }

    /** @throws LauncherException if the service negotiated something other than protocol 1 on the automation surface */
    static void check(Value body) {
        long version = WireValues.integer(body, "protocolVersion", -1L);
        String surface = WireValues.string(body, "surface", "");
        if (version != LauncherProtocol.PROTOCOL_VERSION || !surface.equals(LauncherProtocol.SURFACE_AUTOMATION)) {
            throw new LauncherException(PROTOCOL_MISMATCH, "hello negotiated protocol " + version
                    + " on surface '" + surface + "'; this host speaks protocol "
                    + LauncherProtocol.PROTOCOL_VERSION + " on the automation surface");
        }
        log.info("Launcher service {} accepted this host (connection {}, {} methods)",
                WireValues.string(body, "serviceVersion", "?"), WireValues.integer(body, "connectionId", 0L),
                WireValues.array(body, "methods").size());
    }
}
