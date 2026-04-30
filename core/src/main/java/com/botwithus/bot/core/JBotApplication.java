package com.botwithus.bot.core;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.impl.ClientImpl;
import com.botwithus.bot.core.impl.ClientProviderImpl;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.GameAPIImpl;
import com.botwithus.bot.core.impl.MessageBusImpl;
import com.botwithus.bot.core.impl.ScriptContextImpl;
import com.botwithus.bot.core.impl.ScriptManagerImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.SDNScriptLoader;
import com.botwithus.bot.core.runtime.ScriptRuntime;
import com.botwithus.bot.core.shm.SharedRegion;
import com.botwithus.bot.core.shm.SharedRegionEventPump;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Entry point: connects the pipe, loads scripts, and starts the runtime.
 */
public class JBotApplication {

    private static final Logger log = LoggerFactory.getLogger(JBotApplication.class);

    public static void main(String[] args) {
        // Producer publishes BotWithUs_<pid> per injected game — discover the
        // first one rather than hard-coding the legacy single name. Failure
        // here means no game has the DLL loaded, which we surface to the
        // operator via the catch below.
        String pipeName = PipeClient.firstAvailableOrThrow();
        long pid = SharedRegion.parsePid(pipeName).orElseThrow(() ->
                new IllegalStateException("Discovered pipe '" + pipeName + "' has no embedded pid"));
        log.info("Connecting to pipe {} (pid={})", pipeName, pid);
        try (PipeClient pipe = new PipeClient(pipeName)) {
            RpcClient rpc = new RpcClient(pipe);
            EventBusImpl eventBus = new EventBusImpl();
            MessageBusImpl messageBus = new MessageBusImpl();
            GameAPIImpl gameAPI = new GameAPIImpl(rpc);
            ClientProviderImpl clientProvider = new ClientProviderImpl();
            clientProvider.putClient(pipeName, new ClientImpl(pipeName, gameAPI, eventBus, pipe::isOpen));
            ScriptContextImpl context = new ScriptContextImpl(gameAPI, eventBus, messageBus, clientProvider);

            rpc.start();

            // Game events arrive via the SHM ring; the pipe is RPC-only.
            SharedRegionEventPump pump = new SharedRegionEventPump(pid, eventBus::publish);

            // Discover scripts from scripts/ directory (drop JARs there)
            List<BotScript> scripts = SDNScriptLoader.loadScripts();
            log.info("Discovered {} script(s)", scripts.size());

            ScriptRuntime runtime = new ScriptRuntime(context);

            // Wire up ScriptManager so scripts can manage other scripts
            ScriptManagerImpl scriptManager = new ScriptManagerImpl(runtime);
            context.setScriptManager(scriptManager);

            runtime.startAll(scripts);

            // Keep main thread alive until interrupted
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down...");
                scriptManager.shutdown();
                runtime.stopAll();
                pump.close();
                rpc.close();
            }));

            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Fatal error: {}", e.getMessage(), e);
        }
    }
}
