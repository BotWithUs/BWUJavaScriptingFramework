package com.botwithus.bot.core.runtime;

import com.botwithus.bot.api.BotScript;

import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;

/**
 * The host half of {@link ScriptModuleBoundaryTest}, run in a JVM of its own.
 *
 * <p>The test patches this class into {@value HostInternalsCheck#HOST_MODULE}
 * and starts it from the module path, so scripts are loaded exactly as the
 * host loads them: by {@link LocalScriptLoader}, each in a child layer of the
 * boot layer that holds the real host module and its real descriptor. It then
 * runs one loop of every script it got, and of every script the boot layer
 * itself provides, and prints what happened.</p>
 *
 * <p>Standard output is this process's reply to the test, one line per module,
 * not logging: {@value #OUTCOME_PREFIX}{@code <layer> <module>=<outcome>}, and
 * {@value #FAILURE_PREFIX}{@code <jar>=<why>} for a JAR that did not load.</p>
 */
public final class ScriptBoundaryHost {

    /** Starts a line that reports one module's outcome. */
    static final String OUTCOME_PREFIX = "boundary-outcome ";
    /** Starts a line that reports a JAR the loader refused. */
    static final String FAILURE_PREFIX = "boundary-failure ";
    /** The outcome of a loop that returned. */
    static final String ALLOWED = "allowed";
    /** Names a module of the boot layer, which is the host's own. */
    static final String HOST_LAYER = "host";
    /** Names a module of a script layer. */
    static final String SCRIPT_LAYER = "script";

    private static final String CAUSE_SEPARATOR = "/";

    private ScriptBoundaryHost() {}

    /** @param args the scripts directory */
    public static void main(String[] args) {
        LoadReport report = LocalScriptLoader.loadReport(Path.of(args[0]));
        Map<String, String> outcomes = new TreeMap<>();
        report.scripts().forEach(script -> record(outcomes, script));
        ServiceLoader.load(ModuleLayer.boot(), BotScript.class).forEach(script -> record(outcomes, script));
        outcomes.forEach((module, outcome) -> System.out.println(OUTCOME_PREFIX + module + "=" + outcome));
        report.failures().forEach(failure -> System.out.println(FAILURE_PREFIX
                + failure.jar().getFileName() + "=" + failure.error().map(Throwable::getMessage).orElse("")));
    }

    private static void record(Map<String, String> outcomes, BotScript script) {
        Module module = script.getClass().getModule();
        String layer = module.getLayer() == ModuleLayer.boot() ? HOST_LAYER : SCRIPT_LAYER;
        outcomes.computeIfAbsent(layer + " " + module.getName(), key -> attempt(script));
    }

    /** {@value #ALLOWED}, or the class of what the loop threw followed by its causes'. */
    private static String attempt(BotScript script) {
        try {
            script.onLoop();
            return ALLOWED;
        } catch (Throwable thrown) {
            StringBuilder chain = new StringBuilder(thrown.getClass().getName());
            for (Throwable cause = thrown.getCause(); cause != null; cause = cause.getCause()) {
                chain.append(CAUSE_SEPARATOR).append(cause.getClass().getName());
            }
            return chain.toString();
        }
    }
}
