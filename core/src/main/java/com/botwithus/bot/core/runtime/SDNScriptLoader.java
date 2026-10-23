package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.crypto.DefinedBundle;
import com.botwithus.bot.core.crypto.SdnDiskBundleSource;
import com.botwithus.bot.core.crypto.SdnLoader;
import com.botwithus.bot.core.rpc.RpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Supplier;

/**
 * Facade that combines local script loading (JAR files) and SDN script loading
 * (encrypted bundles from the server). Each source is handled by its own loader.
 *
 * <p>Use {@link #loadLocalScripts()} for local-only or {@link #loadSdnScripts(RpcClient)}
 * for SDN-only. Use {@link #loadAllScripts(RpcClient)} to load from both sources.
 */
public final class SDNScriptLoader {

    private static final Logger log = LoggerFactory.getLogger(SDNScriptLoader.class);
    // rule-exception: §Banned 5 (mutable static). One-shot gate for the
    // process-global SdnLoader.lockdown() native call — once enforced, no
    // unsigned DLL can ever load again in this process, so the flag is
    // intrinsically process-scoped. See JBotWithUsV2/CLAUDE.md → "Java
    // rules exceptions".
    private static volatile boolean lockdownCalled = false;

    /**
     * Debug-only escape hatch: when {@code true}, a failure to arm process
     * lockdown is logged and tolerated instead of aborting the load. Leaving
     * this on in production defeats the unsigned-DLL guard.
     */
    public static final String LOCKDOWN_OPTIONAL_PROP = "botwithus.sdn.lockdown.optional";

    /**
     * How long one delivered script may take to construct before the install gives
     * up on it. Generous: a constructor that builds a large script's graph runs well
     * inside it, so only one that blocks reaches it.
     */
    public static final Duration CONSTRUCT_BUDGET = Duration.ofSeconds(30);

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
        allScripts.addAll(loadSdnScriptsFromDisk());

        enforceLockdown();
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
     * Loads local scripts and returns the full per-JAR {@link LoadReport}.
     * Enforces process lockdown after loading completes, exactly like
     * {@link #loadScripts()}.
     */
    public static LoadReport loadLocalReport() {
        LoadReport report = LocalScriptLoader.loadReport();
        enforceLockdown();
        return report;
    }

    /**
     * Loads scripts from the SDN via encrypted bundle transfer.
     * Performs the key exchange with the server and has the runtime decrypt the
     * script bundle.
     *
     * @param rpc the RPC client connected to the game server
     * @return list of BotScript implementations from the SDN bundle
     */
    public static List<BotScript> loadSdnScripts(RpcClient rpc) {
        try {
            SdnLoader sdnLoader = new SdnLoader(rpc);
            ClassLoader sdnClassLoader = sdnLoader.loadScriptBundle(
                    SDNScriptLoader.class.getClassLoader());

            List<BotScript> scripts = providersFrom(sdnClassLoader).stream()
                    .map(ServiceLoader.Provider::get)
                    .toList();

            if (scripts.isEmpty()) {
                log.info("No BotScript providers found in SDN bundle.");
            } else {
                log.info("Loaded {} script(s) from SDN.", scripts.size());
            }

            return scripts;
        } catch (Exception | ServiceConfigurationError e) {
            log.error("SDN script loading failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Loads scripts from an SDN bundle delivered to disk by the launcher's
     * file-courier (see {@link SdnDiskBundleSource}). Returns empty unless
     * {@code -Dbotwithus.sdn.disk=true}; on timeout/error it returns empty so the
     * host falls back to local scripts.
     *
     * @return list of BotScript implementations from the disk-delivered bundle
     */
    public static List<BotScript> loadSdnScriptsFromDisk() {
        return loadSdnDeliveryFromDisk().scripts();
    }

    /**
     * As {@link #loadSdnScriptsFromDisk()}, keeping each script with the jar it
     * came from and that jar's place in the delivery, which is what pairs a
     * script with the catalogue entry it was requested as.
     *
     * @return the delivery; {@link SdnDelivery#none()} when disabled, not
     *         delivered, or on error
     */
    public static SdnDelivery loadSdnDeliveryFromDisk() {
        if (!SdnDiskBundleSource.isEnabled()) {
            return SdnDelivery.none();
        }
        try {
            return toDelivery(SdnDiskBundleSource.awaitBundle(SDNScriptLoader.class.getClassLoader()));
        } catch (Exception e) {
            log.error("SDN disk script loading failed: {}", e.getMessage());
            return SdnDelivery.none();
        }
    }

    /**
     * Loads the scripts in a bundle that has already arrived, which is how the
     * launcher's pipe delivers one (amendment B1 of the launcher's ADR 0007).
     * The bytes are the same a file delivery holds and are defined the same way.
     *
     * @return the delivery; {@link SdnDelivery#none()} on error
     */
    public static SdnDelivery loadSdnDelivery(byte[] bundle) {
        try {
            return toDelivery(SdnDiskBundleSource.defineBundle(bundle, SDNScriptLoader.class.getClassLoader()));
        } catch (Exception e) {
            log.error("SDN bundle loading failed: {}", e.getMessage());
            return SdnDelivery.none();
        }
    }

    private static SdnDelivery toDelivery(DefinedBundle bundle) {
        List<SdnDelivery.Jar> jars = new ArrayList<>();
        List<SdnDelivery.Stall> stalls = new ArrayList<>();
        for (DefinedBundle.Jar jar : bundle.jars()) {
            switch (jarOf(jar, CONSTRUCT_BUDGET)) {
                case JarOutcome.Loaded loaded -> jars.add(loaded.jar());
                case JarOutcome.Stalled stalled -> stalls.add(stalled.stall());
                case JarOutcome.Empty ignored -> { }
            }
        }
        SdnDelivery delivery = new SdnDelivery(bundle.jarCount(), jars, stalls);
        if (bundle.jarCount() > 0 && delivery.isEmpty()) {
            log.info("No BotScript providers found in SDN bundle.");
        } else if (!delivery.isEmpty()) {
            log.info("Loaded {} script(s) from {} SDN jar(s).", delivery.scripts().size(), jars.size());
        }
        return delivery;
    }

    /** What one defined jar came to. */
    private sealed interface JarOutcome {
        record Loaded(SdnDelivery.Jar jar) implements JarOutcome {}
        record Stalled(SdnDelivery.Stall stall) implements JarOutcome {}
        record Empty() implements JarOutcome {}
    }

    /**
     * The scripts one defined jar provides, each made once within {@code budget}
     * and kept with its provider so more instances can be made later. A provider
     * that throws is skipped; one that does not return stops the jar.
     */
    private static JarOutcome jarOf(DefinedBundle.Jar jar, Duration budget) {
        log.info("SDN: jar {}: looking up its script providers", jar.position() + 1);
        List<ServiceLoader.Provider<BotScript>> providers = providersFrom(jar.loader());
        log.info("SDN: jar {}: {} provider(s) found", jar.position() + 1, providers.size());
        List<BotScript> scripts = new ArrayList<>();
        List<Supplier<BotScript>> copies = new ArrayList<>();
        for (ServiceLoader.Provider<BotScript> provider : providers) {
            String cls = provider.type().getName();
            log.info("SDN: constructing {}", cls);
            switch (BoundedConstruction.construct(provider.type().getSimpleName(), provider::get, budget)) {
                case BoundedConstruction.Outcome.Built<BotScript> built -> {
                    log.info("SDN: constructed {} in {} ms", cls, built.took().toMillis());
                    scripts.add(built.instance());
                    copies.add(provider::get);
                }
                case BoundedConstruction.Outcome.Threw<BotScript> threw ->
                        log.warn("SDN: {} threw while constructing, skipping it", cls, threw.error());
                case BoundedConstruction.Outcome.TimedOut<BotScript> timedOut -> {
                    log.error("SDN: {} did not finish constructing within {} s; its thread is left running "
                            + "and the host must be restarted to load it", cls, timedOut.budget().toSeconds());
                    return new JarOutcome.Stalled(new SdnDelivery.Stall(jar.position(), cls));
                }
            }
        }
        return scripts.isEmpty() ? new JarOutcome.Empty()
                : new JarOutcome.Loaded(new SdnDelivery.Jar(jar.position(), scripts, copies));
    }

    /**
     * Discovers the providers in one delivered bundle: from its own module layer
     * when the jar declares a module, else from its unnamed module (see
     * {@link SdnBundleProviders}).
     *
     * <p>Failure is contained to this loader. A module that does not resolve, a
     * descriptor that does not unseal, or a provider whose class is missing or
     * whose constructor throws surfaces here — and without this boundary it
     * would cost the user every other script in the same delivery. That
     * isolation is the reason the delivery is N loaders rather than one merged
     * jar.
     */
    private static List<ServiceLoader.Provider<BotScript>> providersFrom(ClassLoader loader) {
        try {
            return SdnBundleProviders.providers(loader, SdnLoader.moduleOf(loader), ModuleLayer.boot(),
                    BotScript.class);
        } catch (Exception | ServiceConfigurationError | LinkageError e) {
            log.warn("SDN: a delivered bundle yielded no usable providers, skipping it: {}",
                    e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Loads all BotScript providers from local JARs in the default {@code scripts/} directory.
     * This is the legacy entry point — equivalent to {@link #loadLocalScripts()}.
     */
    public static List<BotScript> loadScripts() {
        List<BotScript> scripts = loadLocalScripts();
        enforceLockdown();
        return scripts;
    }

    /**
     * Loads all BotScript providers from local JARs in the given directory.
     * This is the legacy entry point — equivalent to {@link #loadLocalScripts(Path)}.
     */
    public static List<BotScript> loadScripts(Path scriptsDir) {
        List<BotScript> scripts = loadLocalScripts(scriptsDir);
        enforceLockdown();
        return scripts;
    }

    /**
     * Enforces process lockdown after scripts are loaded to prevent unsigned
     * DLL loading.
     *
     * <p>Lockdown is armed unconditionally once the initial load decision is
     * made — NOT gated on scripts being present. An earlier
     * {@code !scripts.isEmpty()} guard meant an empty-scripts session never
     * armed it, leaving unsigned-DLL loading open for the whole session.</p>
     *
     * <p>On a JVM that provides the SDN loader, a failure to arm is fatal:
     * continuing would run scripts with the very protection they depend on
     * silently disabled, which is exactly the state an attacker wants. On a
     * stock JDK there is nothing to arm, so this is a no-op.</p>
     *
     * @throws IllegalStateException if lockdown is available but could not be
     *         armed, unless {@code -D}{@value #LOCKDOWN_OPTIONAL_PROP}{@code =true}
     */
    private static void enforceLockdown() {
        if (lockdownCalled) {
            return;
        }
        if (!SdnLoader.isAvailable()) {
            log.debug("SDN class loader absent (stock JDK) — process lockdown not applicable.");
            return;
        }
        try {
            SdnLoader.lockdown();
            lockdownCalled = true;
            log.info("Process lockdown enforced — unsigned DLL loading blocked.");
        } catch (Exception e) {
            if (Boolean.getBoolean(LOCKDOWN_OPTIONAL_PROP)) {
                log.error("lockdown0() failed: {} — continuing anyway because -D{}=true. "
                        + "Unsigned DLLs can still load in this process.", e.getMessage(),
                        LOCKDOWN_OPTIONAL_PROP);
                return;
            }
            throw new IllegalStateException(
                    "Process lockdown failed on an SDN-capable JVM; refusing to run scripts "
                            + "with unsigned-DLL loading left open. Set -D"
                            + LOCKDOWN_OPTIONAL_PROP + "=true to override for debugging.", e);
        }
    }
}
