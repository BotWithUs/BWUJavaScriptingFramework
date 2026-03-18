package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.crypto.SdnLoader;
import com.botwithus.bot.core.rpc.RpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Facade that combines local script loading (JAR files) and SDN script loading
 * (encrypted bundles from the server). Each source is handled by its own loader.
 *
 * <p>Use {@link #loadLocalScripts()} for local-only or {@link #loadSdnScripts(RpcClient)}
 * for SDN-only. Use {@link #loadAllScripts(RpcClient)} to load from both sources.
 */
public final class SDNScriptLoader {

    private static final Logger log = LoggerFactory.getLogger(SDNScriptLoader.class);
    private static volatile boolean lockdownCalled = false;

    private SDNScriptLoader() {}

    /**
     * Loads scripts from both local JARs and SDN (if an RPC connection is available).
     *
     * @param rpc the RPC client for SDN script fetching, or {@code null} to skip SDN
     * @return combined list of scripts from all sources
     */
    public static List<BotScript> loadAllScripts(RpcClient rpc) {
        List<BotScript> allScripts = new ArrayList<>(loadLocalScripts());

        if (rpc != null) {
            allScripts.addAll(loadSdnScripts(rpc));
        }

        enforceLockdown(allScripts);
        return allScripts;
    }

    /**
     * Loads all BotScript providers from local JARs in the default {@code scripts/} directory.
     */
    public static List<BotScript> loadLocalScripts() {
        return LocalScriptLoader.loadScripts();
    }

    /**
     * Loads all BotScript providers from local JARs in the given directory.
     */
    public static List<BotScript> loadLocalScripts(Path scriptsDir) {
        return LocalScriptLoader.loadScripts(scriptsDir);
    }

    /**
     * Loads scripts from the SDN via encrypted bundle transfer.
     * Performs ECDH key exchange with the server and decrypts the script bundle.
     *
     * @param rpc the RPC client connected to the game server
     * @return list of BotScript implementations from the SDN bundle
     */
    public static List<BotScript> loadSdnScripts(RpcClient rpc) {
        try {
            SdnLoader sdnLoader = new SdnLoader(rpc);
            ClassLoader sdnClassLoader = sdnLoader.loadScriptBundle(
                    SDNScriptLoader.class.getClassLoader());

            List<BotScript> scripts = new ArrayList<>();
            ServiceLoader<BotScript> loader = ServiceLoader.load(BotScript.class, sdnClassLoader);
            for (BotScript script : loader) {
                scripts.add(script);
                log.info("SDN loaded: {}", script.getClass().getName());
            }

            if (scripts.isEmpty()) {
                log.info("No BotScript providers found in SDN bundle.");
            } else {
                log.info("Loaded {} script(s) from SDN.", scripts.size());
            }

            return scripts;
        } catch (Exception e) {
            log.error("SDN script loading failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Loads all BotScript providers from local JARs in the default {@code scripts/} directory.
     * This is the legacy entry point — equivalent to {@link #loadLocalScripts()}.
     */
    public static List<BotScript> loadScripts() {
        List<BotScript> scripts = loadLocalScripts();
        enforceLockdown(scripts);
        return scripts;
    }

    /**
     * Loads all BotScript providers from local JARs in the given directory.
     * This is the legacy entry point — equivalent to {@link #loadLocalScripts(Path)}.
     */
    public static List<BotScript> loadScripts(Path scriptsDir) {
        List<BotScript> scripts = loadLocalScripts(scriptsDir);
        enforceLockdown(scripts);
        return scripts;
    }

    /**
     * Enforces process lockdown after scripts are loaded to prevent unsigned DLL loading.
     */
    private static void enforceLockdown(List<BotScript> scripts) {
        if (!scripts.isEmpty() && !lockdownCalled) {
            try {
                SdnLoader.lockdown();
                lockdownCalled = true;
                log.info("Process lockdown enforced — unsigned DLL loading blocked.");
            } catch (Exception e) {
                log.error("lockdown0() failed: {}", e.getMessage());
            }
        }
    }
}
