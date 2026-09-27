package com.botwithus.bot.cli.gui.pages.installed;

import com.botwithus.bot.cli.events.ClientKey;
import com.botwithus.bot.cli.gui.pages.installed.StartTarget.Eligibility;

import java.util.List;
import java.util.Optional;

/** Who the Start-on dialog offers a script to, and who it cannot. */
public final class StartTargets {

    private StartTargets() {}

    /**
     * One target per client, in {@code clients} order.
     *
     * @param script  the script to start
     * @param clients every client the host knows, connected or not
     */
    public static List<StartTarget> of(InstalledScript script, List<ClientChoice> clients) {
        return clients.stream().map(c -> targetFor(script, c)).toList();
    }

    private static StartTarget targetFor(InstalledScript script, ClientChoice client) {
        if (!client.isConnected()) {
            return notConnected(script, client);
        }
        Optional<RunnerState> here = script.runs().stream()
                .filter(r -> r.clientId().equals(client.clientId()))
                .map(ClientRun::state)
                .findFirst();
        if (here.isPresent()) {
            return onClientThatRanIt(script, client, here.get());
        }
        boolean isInstalledHere = script.provenance().source() == ScriptSource.LOCAL
                || script.registeredOn().contains(client.clientId());
        return isInstalledHere
                ? target(client, Eligibility.AVAILABLE, "idle")
                : target(client, Eligibility.NOT_INSTALLED_HERE, "not installed here");
    }

    private static StartTarget onClientThatRanIt(InstalledScript script, ClientChoice client,
                                                 RunnerState state) {
        return switch (state) {
            case RUNNING, STALLED -> target(client, Eligibility.ALREADY_RUNNING, "already running");
            case CUT_OFF -> target(client, Eligibility.SHUTTING_DOWN, "still shutting down");
            case CRASHED -> target(client, Eligibility.AVAILABLE, "crashed here");
            case STOPPED -> target(client, Eligibility.AVAILABLE, "stopped here");
            case OFFLINE -> notConnected(script, client);
        };
    }

    /**
     * A client that is not connected can have the script queued for when it is
     * back, if the host remembers its account; a Store delivery cannot wait,
     * as it reaches only connected clients.
     */
    private static StartTarget notConnected(InstalledScript script, ClientChoice client) {
        if (script.provenance().source() == ScriptSource.STORE) {
            return target(client, Eligibility.OFFLINE_STORE_SCRIPT, client.offlineNote());
        }
        return switch (client.key()) {
            case ClientKey.Account account when account.isRemembered() ->
                    target(client, Eligibility.WHEN_BACK, "starts when back");
            case ClientKey.Account _ -> target(client, Eligibility.OFFLINE_SECOND_CLIENT, client.offlineNote());
            case ClientKey.Pipe _ -> target(client, Eligibility.OFFLINE_NO_ACCOUNT, client.offlineNote());
        };
    }

    private static StartTarget target(ClientChoice client, Eligibility eligibility, String note) {
        return new StartTarget(client.clientId(), client.name(), client.isConnected(), eligibility, note);
    }
}
