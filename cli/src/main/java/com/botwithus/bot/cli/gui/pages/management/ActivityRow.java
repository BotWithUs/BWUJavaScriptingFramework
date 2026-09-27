package com.botwithus.bot.cli.gui.pages.management;

import java.util.Objects;

/**
 * One orchestrator call a management script made, as the Activity tab lists it.
 *
 * @param time   when, as a wall clock: "14:05"
 * @param call   the orchestrator method: {@code stopScript}
 * @param target what it named: "Woodcutting on Duskwater"
 * @param result how it went: "ok", or why it was refused
 */
public record ActivityRow(String time, String call, String target, String result) {

    public ActivityRow {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(result, "result");
    }

    /** The line after the time: "stopScript · Woodcutting on Duskwater". */
    public String what() {
        return target.isEmpty() ? call : call + " · " + target;
    }
}
