package com.botwithus.bot.core;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.impl.ClientImpl;
import com.botwithus.bot.core.impl.ClientProviderImpl;
import com.botwithus.bot.core.impl.EventBusImpl;
import com.botwithus.bot.core.impl.EventDispatcher;
import com.botwithus.bot.core.impl.GameAPIImpl;
import com.botwithus.bot.core.impl.MessageBusImpl;
import com.botwithus.bot.core.impl.ScriptContextImpl;
import com.botwithus.bot.core.impl.ScriptManagerImpl;
import com.botwithus.bot.core.pipe.PipeClient;
import com.botwithus.bot.core.rpc.RpcClient;
import com.botwithus.bot.core.runtime.SDNScriptLoader;
import com.botwithus.bot.core.runtime.ScriptRuntime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Entry point: connects the pipe, loads scripts, and starts the runtime.
 */
public class JBotApplication {

    private static final Logger log = LoggerFactory.getLogger(JBotApplication.class);

    public static void main(String[] args) {
        log.info("Connecting to BotWithUs pipe...");
        try (PipeClient pipe = new PipeClient()) {
            RpcClient rpc = new RpcClient(pipe);
            EventBusImpl eventBus = new EventBusImpl();
            MessageBusImpl messageBus = new MessageBusImpl();
            GameAPIImpl gameAPI = new GameAPIImpl(rpc);
            ClientProviderImpl clientProvider = new ClientProviderImpl();
            clientProvider.putClient("BotWithUs", new ClientImpl("BotWithUs", gameAPI, eventBus, pipe::isOpen));
            ScriptContextImpl context = new ScriptContextImpl(gameAPI, eventBus, messageBus, clientProvider);

            // Route pipe events to the typed event bus and enable auto-subscription
            EventDispatcher dispatcher = new EventDispatcher(eventBus);
            dispatcher.bindAutoSubscription(gameAPI);
            rpc.setEventHandler(dispatcher::dispatch);
            rpc.start();

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
