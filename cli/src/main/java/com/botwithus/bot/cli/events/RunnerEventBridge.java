package com.botwithus.bot.cli.events;

import com.botwithus.bot.api.runtime.LastCrash;
import com.botwithus.bot.cli.events.HostEvent.ManagementScriptCrashed;
import com.botwithus.bot.cli.events.HostEvent.ScriptStalled;
import com.botwithus.bot.cli.events.HostEvent.ScriptStarted;
import com.botwithus.bot.cli.events.HostEvent.ScriptStopped;
import com.botwithus.bot.core.runtime.RunnerListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/**
 * Turns the script runtime's lifecycle callbacks into {@link HostEvent}s. One
 * instance serves every connection's runtime and the management runtime.
 */
public final class RunnerEventBridge implements RunnerListener {

    private static final Logger log = LoggerFactory.getLogger(RunnerEventBridge.class);

    private final HostEventBus bus;
    private final Clock clock;

    public RunnerEventBridge(HostEventBus bus, Clock clock) {
        this.bus = bus;
        this.clock = clock;
    }

    @Override
    public void scriptStarted(String connectionName, String scriptName) {
        if (isBound(connectionName, scriptName)) {
            ClientRef client = new ClientRef(connectionName);
            bus.publish(new ScriptStarted(client, scriptName, clock.instant()));
        }
    }

    @Override
    public void scriptStopped(String connectionName, String scriptName) {
        if (isBound(connectionName, scriptName)) {
            ClientRef client = new ClientRef(connectionName);
            bus.publish(new ScriptStopped(client, scriptName, clock.instant()));
        }
    }

    @Override
    public void scriptStalled(String connectionName, String scriptName) {
        if (isBound(connectionName, scriptName)) {
            ClientRef client = new ClientRef(connectionName);
            bus.publish(new ScriptStalled(client, scriptName, clock.instant()));
        }
    }

    @Override
    public void managementScriptCrashed(String scriptName, LastCrash crash) {
        bus.publish(new ManagementScriptCrashed(scriptName, crash, clock.instant()));
    }

    /** A runner not yet bound to a connection has no client to report against. */
    private static boolean isBound(String connectionName, String scriptName) {
        if (connectionName == null) {
            log.debug("Runner {} has no connection; lifecycle event not published", scriptName);
            return false;
        }
        return true;
    }
}
