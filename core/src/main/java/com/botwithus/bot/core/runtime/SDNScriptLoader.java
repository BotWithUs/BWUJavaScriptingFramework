package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.crypto.SdnLoader;
import com.botwithus.bot.core.rpc.RpcClient;

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
                System.out.println("[ScriptLoader] SDN loaded: " + script.getClass().getName());
            }

            if (scripts.isEmpty()) {
                System.out.println("[ScriptLoader] No BotScript providers found in SDN bundle.");
            } else {
                System.out.println("[ScriptLoader] Loaded " + scripts.size() + " script(s) from SDN.");
            }

            return scripts;
        } catch (Exception e) {
            System.err.println("[ScriptLoader] SDN script loading failed: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Loads all BotScript providers from local JARs in the default {@code scripts/} directory.
     * This is the legacy entry point - equivalent to {@link #loadLocalScripts()}.
     */
    public static List<BotScript> loadScripts() {
        List<BotScript> scripts = loadLocalScripts();
        enforceLockdown(scripts);
        return scripts;
    }

    /**
     * Loads all BotScript providers from local JARs in the given directory.
     * This is the legacy entry point - equivalent to {@link #loadLocalScripts(Path)}.
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
            if (!SdnLoader.isLockdownAvailable()) {
                lockdownCalled = true;
                System.out.println("[ScriptLoader] Process lockdown unavailable on this JVM; continuing without DLL lockdown.");
                return;
            }
            try {
                SdnLoader.lockdown();
                lockdownCalled = true;
                System.out.println("[ScriptLoader] Process lockdown enforced - unsigned DLL loading blocked.");
            } catch (Exception e) {
                lockdownCalled = true;
                System.err.println("[ScriptLoader] Process lockdown unavailable: " + rootCauseMessage(e));
            }
        }
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
