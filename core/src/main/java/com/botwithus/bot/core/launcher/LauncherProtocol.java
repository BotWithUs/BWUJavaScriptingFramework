package com.botwithus.bot.core.launcher;

import java.util.List;

/**
 * Names and limits of the launcher service's automation pipe, protocol 1.
 *
 * <p>This is the consumer side of a contract the BotWithUs launcher owns: its
 * ADR 0007, sections 2 and 4, and the golden fixtures this module's tests vendor
 * (see {@code src/test/resources/launcher-protocol/VENDOR.md}). A change on the
 * launcher side lands there first, and these constants follow it.</p>
 */
public final class LauncherProtocol {

    /** The only protocol version this host speaks. */
    public static final int PROTOCOL_VERSION = 1;
    /** The envelope's {@code v}; not the protocol version. */
    public static final int ENVELOPE_VERSION = 1;
    /** Largest frame body either side sends: 1 MiB. */
    public static final int MAX_BODY_BYTES = 1024 * 1024;
    /** Largest request id; ids then wrap back to 1. */
    public static final int MAX_REQUEST_ID = Integer.MAX_VALUE;
    /** Longest {@code hostLabel} the service keeps, in code points. */
    public static final int MAX_HOST_LABEL_CODE_POINTS = 64;

    /** {@code hello}'s {@code clientKind} on the automation pipe. */
    public static final String CLIENT_KIND_HOST = "host";
    /** {@code hello}'s {@code hostKind} for this host. */
    public static final String HOST_KIND_JAVA = "java";
    /** The surface the automation pipe reports in {@code hello}. */
    public static final String SURFACE_AUTOMATION = "automation";

    public static final String METHOD_HELLO = "hello";
    public static final String METHOD_SERVICE_STATUS = "service.status";
    public static final String METHOD_ACCOUNTS_LIST = "accounts.list";
    public static final String METHOD_CLIENT_LAUNCH = "client.launch";
    public static final String METHOD_CLIENT_STOP = "client.stop";
    public static final String METHOD_CLIENT_LIST = "client.list";
    public static final String METHOD_CLIENT_STATUS = "client.status";
    public static final String METHOD_EVENTS_SUBSCRIBE = "events.subscribe";
    public static final String METHOD_HOST_ACK_CLOSE = "host.ack_close";
    /**
     * Asks for a Store bundle sealed to this host's key (amendment B1). Offered
     * only by a launcher that serves bundles over the pipe, which a host learns
     * from {@code hello}'s {@code methods}; such a launcher serves none by file.
     */
    public static final String METHOD_SDN_BUNDLE = "sdn.bundle";
    /** One chunk of the bundle {@link #METHOD_SDN_BUNDLE} offered. */
    public static final String METHOD_SDN_BUNDLE_CHUNK = "sdn.bundle_chunk";

    /**
     * Every refusal and failure of the two SDN methods. The service says nothing
     * more about the cause, and this host neither shows nor logs more than the code.
     */
    public static final String ERROR_SDN_NOT_DELIVERED = "sdn_not_delivered";

    /** The length of the key {@code sdn.bundle} seals to. */
    public static final int SDN_PUBLIC_KEY_BYTES = 32;
    /** The length of the offer's {@code sha256}. */
    public static final int SDN_SHA256_BYTES = 32;
    /** The largest bundle the service offers: 64 MiB. */
    public static final long SDN_MAX_BUNDLE_BYTES = 64L * 1024 * 1024;

    public static final String EVENT_CLIENT_STARTED = "client.started";
    public static final String EVENT_CLIENT_STATE = "client.state";
    public static final String EVENT_CLIENT_EXITED = "client.exited";
    public static final String EVENT_AGENT_UPDATED = "agent.updated";
    public static final String EVENT_DATA_UPDATE_AVAILABLE = "data.update_available";
    public static final String EVENT_DATA_UPDATE_APPLIED = "data.update_applied";
    /**
     * The native host package's update, on the {@code native} topic. Not the
     * data update: this host neither installs that package nor subscribes to
     * the topic, so it never turns these into script events.
     */
    public static final String EVENT_NATIVE_UPDATE_AVAILABLE = "native.update_available";
    public static final String EVENT_NATIVE_UPDATE_APPLIED = "native.update_applied";
    public static final String EVENT_LICENCE_STATE = "licence.state";
    public static final String EVENT_SERVICE_SHUTTING_DOWN = "service.shutting_down";
    /** Sent only to this host's own connection, whether or not it subscribed. */
    public static final String EVENT_HOST_CLOSE_REQUESTED = "host.close_requested";

    /**
     * The topics this host subscribes to: every automation topic except
     * {@code native}, whose events concern only the native host's package.
     * {@code hosts} is refused on the automation surface. A service older than
     * a topic refuses the whole subscribe with {@code bad_request}, so adding
     * {@code native} here needs a resubscribe without it on that refusal.
     */
    public static final List<String> AUTOMATION_TOPICS = List.of("clients", "agent", "data", "licence", "service");

    private LauncherProtocol() {
    }

    /** @return the automation pipe's name for {@code scope}, without the pipe namespace prefix */
    public static String automationPipeName(String scope) {
        return "bwu_svc_auto_" + scope;
    }
}
