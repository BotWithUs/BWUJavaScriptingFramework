package com.botwithus.bot.cli.scripts;

import com.botwithus.bot.core.runtime.ScriptRuntime;

/**
 * One client a reload applies to.
 *
 * @param connection the client's connection name, as reported back in {@link RunningPair}
 * @param runtime    the client's script runtime
 */
public record ReloadTarget(String connection, ScriptRuntime runtime) {
}
