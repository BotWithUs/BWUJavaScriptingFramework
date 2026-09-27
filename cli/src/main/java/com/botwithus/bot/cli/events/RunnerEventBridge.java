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
import java.time.Instant;

/**
 * Turns the script runtime's lifecycle callbacks into {@link HostEvent}s. One
 * instance serves every connection's runtime and the management runtime.
 */
public final class RunnerEventBridge implements RunnerListener {

    private static final Logger log = LoggerFactory.getLogger(RunnerEventBridge.class);

    private final HostEventBus bus;
    private final ClientKeys keys;
    private final Clock clock;

    /**
     * @param bus  where host-wide events go
     * @param keys keys each client event by the client it is about, and publishes it
     */
    public RunnerEventBridge(HostEventBus bus, ClientKeys keys, Clock clock) {
        this.bus = bus;
        this.keys = keys;
        this.clock = clock;
    }

    @Override
    public void scriptStarted(String connectionName, String scriptName) {
        if (isBound(connectionName, scriptName)) {
            Instant at = clock.instant();
            keys.publish(connectionName, client -> new ScriptStarted(client, scriptName, at));
        }
    }

    @Override
    public void scriptStopped(String connectionName, String scriptName) {
        if (isBound(connectionName, scriptName)) {
            Instant at = clock.instant();
            keys.publish(connectionName, client -> new ScriptStopped(client, scriptName, at));
        }
    }

    @Override
    public void scriptStalled(String connectionName, String scriptName) {
        if (isBound(connectionName, scriptName)) {
            Instant at = clock.instant();
            keys.publish(connectionName, client -> new ScriptStalled(client, scriptName, at));
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
