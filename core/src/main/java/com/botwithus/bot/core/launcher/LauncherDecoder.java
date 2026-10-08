package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.LaunchOptions;
import com.botwithus.bot.api.script.LaunchedClient;
import com.botwithus.bot.api.script.LauncherAccount;
import com.botwithus.bot.api.script.LauncherEvent;
import org.msgpack.value.Value;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Turns the service's bodies into the api's records. A field the service did
 * not send reads as its default, and an enum string this host does not know
 * reads as {@code UNKNOWN}: neither is an error (ADR 2.7, additive changes).
 */
final class LauncherDecoder {

    private LauncherDecoder() {
    }

    /** @return the accounts in {@code accounts.list}'s body */
    static List<LauncherAccount> accounts(Value body) {
        return WireValues.array(body, "accounts").stream()
                .filter(Value::isMapValue)
                .map(a -> new LauncherAccount(WireValues.string(a, "id", ""), WireValues.string(a, "name", "")))
                .toList();
    }

    /** @return the clients in {@code client.list}'s body */
    static List<LaunchedClient> clients(Value body) {
        return WireValues.array(body, "clients").stream().filter(Value::isMapValue)
                .map(LauncherDecoder::client).toList();
    }

    /** @return the {@code Client} record (ADR 4.2) */
    static LaunchedClient client(Value c) {
        Optional<LaunchedClient.LaunchedBy> launchedBy = WireValues.field(c, "launchedBy")
                .filter(Value::isMapValue)
                .map(b -> new LaunchedClient.LaunchedBy(WireValues.integer(b, "pid", 0L),
                        WireValues.string(b, "hostKind", ""), WireValues.string(b, "label")));
        Value licence = WireValues.map(c, "licence");
        return new LaunchedClient(WireValues.string(c, "clientId", ""), WireValues.integer(c, "pid", 0L),
                WireValues.string(c, "accountId", ""), WireValues.string(c, "accountName", ""),
                (int) WireValues.integer(c, "characterIndex", LaunchOptions.NO_CHARACTER),
                kind(WireValues.string(c, "kind", "")), origin(WireValues.string(c, "origin", "")),
                launchedBy, WireValues.integer(c, "restartOf"), state(WireValues.string(c, "state", "")),
                WireValues.integer(c, "startedAtMs", 0L), WireValues.string(c, "agentSha", ""),
                WireValues.bool(c, "isAgentStale", false),
                new LaunchedClient.Licence(licenceState(WireValues.string(licence, "state", "")),
                        (int) WireValues.integer(licence, "failures", 0L), WireValues.string(licence, "lastError")));
    }

    /**
     * The api event for a service event. {@code host.close_requested} is not
     * one: it goes to the host, never to scripts.
     *
     * @return the event, or empty for an event this host does not know
     */
    static Optional<LauncherEvent> event(String method, Value body) {
        return switch (method) {
            case LauncherProtocol.EVENT_CLIENT_STARTED -> Optional.of(new LauncherEvent.ClientStarted(client(body)));
            case LauncherProtocol.EVENT_CLIENT_STATE -> Optional.of(clientState(body));
            case LauncherProtocol.EVENT_CLIENT_EXITED -> Optional.of(new LauncherEvent.ClientExited(
                    WireValues.string(body, "clientId", ""), WireValues.integer(body, "exitCode", 0L),
                    exitReason(WireValues.string(body, "reason", ""))));
            case LauncherProtocol.EVENT_AGENT_UPDATED ->
                    Optional.of(new LauncherEvent.AgentUpdated(WireValues.string(body, "sha", "")));
            case LauncherProtocol.EVENT_DATA_UPDATE_AVAILABLE -> Optional.of(dataUpdate(body));
            case LauncherProtocol.EVENT_DATA_UPDATE_APPLIED ->
                    Optional.of(new LauncherEvent.DataUpdateApplied(WireValues.string(body, "sha", "")));
            case LauncherProtocol.EVENT_LICENCE_STATE -> Optional.of(licenceChanged(body));
            case LauncherProtocol.EVENT_SERVICE_SHUTTING_DOWN -> Optional.of(
                    new LauncherEvent.ServiceShuttingDown(WireValues.bool(body, "closeClients", false)));
            // Named, not left to default: these share the data events' body shape
            // but are never a data update.
            case LauncherProtocol.EVENT_NATIVE_UPDATE_AVAILABLE,
                 LauncherProtocol.EVENT_NATIVE_UPDATE_APPLIED -> Optional.empty();
            default -> Optional.empty();
        };
    }

    /** @return the close request in {@code host.close_requested}'s body */
    static CloseRequest closeRequest(Value body) {
        return new CloseRequest(WireValues.integer(body, "requestId", 0L),
                WireValues.string(body, "reason", ""), WireValues.integer(body, "hostsBlocking", 0L));
    }

    private static LauncherEvent clientState(Value body) {
        Value detail = WireValues.map(body, "detail");
        return new LauncherEvent.ClientStateChanged(WireValues.string(body, "clientId", ""),
                state(WireValues.string(body, "state", "")), WireValues.string(detail, "stage"),
                WireValues.string(detail, "code"), WireValues.string(detail, "message"));
    }

    private static LauncherEvent dataUpdate(Value body) {
        return new LauncherEvent.DataUpdateAvailable(dataPhase(WireValues.string(body, "phase", "")),
                WireValues.string(body, "stagedSha"), WireValues.array(body, "blockedBy").size(),
                WireValues.bool(body, "applyWhenHostsClose", false));
    }

    private static LauncherEvent licenceChanged(Value body) {
        OptionalLong failures = WireValues.integer(body, "failures");
        return new LauncherEvent.LicenceChanged(WireValues.string(body, "link", ""),
                WireValues.string(body, "clientId"),
                WireValues.string(body, "state").map(LauncherDecoder::licenceState),
                failures.isPresent() ? OptionalInt.of((int) failures.getAsLong()) : OptionalInt.empty());
    }

    static LaunchedClient.State state(String wire) {
        return switch (wire) {
            case "queued" -> LaunchedClient.State.QUEUED;
            case "spawning" -> LaunchedClient.State.SPAWNING;
            case "injecting" -> LaunchedClient.State.INJECTING;
            case "injected" -> LaunchedClient.State.INJECTED;
            case "failed" -> LaunchedClient.State.FAILED;
            case "exited" -> LaunchedClient.State.EXITED;
            default -> LaunchedClient.State.UNKNOWN;
        };
    }

    private static LaunchedClient.Kind kind(String wire) {
        return switch (wire) {
            case "jagex" -> LaunchedClient.Kind.JAGEX;
            case "steam" -> LaunchedClient.Kind.STEAM;
            case "attached" -> LaunchedClient.Kind.ATTACHED;
            default -> LaunchedClient.Kind.UNKNOWN;
        };
    }

    private static LaunchedClient.Origin origin(String wire) {
        return switch (wire) {
            case "ui" -> LaunchedClient.Origin.UI;
            case "automation" -> LaunchedClient.Origin.AUTOMATION;
            default -> LaunchedClient.Origin.UNKNOWN;
        };
    }

    private static LaunchedClient.LicenceState licenceState(String wire) {
        return switch (wire) {
            case "ok" -> LaunchedClient.LicenceState.OK;
            case "retrying" -> LaunchedClient.LicenceState.RETRYING;
            case "dropped" -> LaunchedClient.LicenceState.DROPPED;
            case "untracked" -> LaunchedClient.LicenceState.UNTRACKED;
            default -> LaunchedClient.LicenceState.UNKNOWN;
        };
    }

    static LauncherEvent.ExitReason exitReason(String wire) {
        return switch (wire) {
            case "stopped" -> LauncherEvent.ExitReason.STOPPED;
            case "licence" -> LauncherEvent.ExitReason.LICENCE;
            case "descriptor" -> LauncherEvent.ExitReason.DESCRIPTOR;
            default -> LauncherEvent.ExitReason.UNKNOWN;
        };
    }

    private static LauncherEvent.DataPhase dataPhase(String wire) {
        return switch (wire) {
            case "current" -> LauncherEvent.DataPhase.CURRENT;
            case "downloading" -> LauncherEvent.DataPhase.DOWNLOADING;
            case "staged" -> LauncherEvent.DataPhase.STAGED;
            case "applying" -> LauncherEvent.DataPhase.APPLYING;
            case "error" -> LauncherEvent.DataPhase.ERROR;
            default -> LauncherEvent.DataPhase.UNKNOWN;
        };
    }
}
