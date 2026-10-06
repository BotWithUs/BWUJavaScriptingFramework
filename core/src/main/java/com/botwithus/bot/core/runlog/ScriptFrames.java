package com.botwithus.bot.core.runlog;

import java.util.function.Predicate;

/**
 * Decides which stack frames are a script's own code, for a crash block's
 * {@code top_frame}: frames in the script's named module, or, for a script with
 * no module of its own (SDN, the classpath), frames in its package or below.
 * Host and JDK frames are skipped either way.
 */
public final class ScriptFrames {

    private ScriptFrames() {
    }

    /** The predicate for {@code scriptClass}'s code. */
    public static Predicate<StackTraceElement> of(Class<?> scriptClass) {
        Module module = scriptClass.getModule();
        String moduleName = module.isNamed() ? module.getName() : null;
        String pkg = scriptClass.getPackageName();
        String prefix = pkg.isEmpty() ? null : pkg + ".";
        return frame -> (moduleName != null && moduleName.equals(frame.getModuleName()))
                || (prefix != null && frame.getClassName().startsWith(prefix))
                || frame.getClassName().equals(scriptClass.getName());
    }
}
