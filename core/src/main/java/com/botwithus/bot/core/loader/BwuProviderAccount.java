package com.botwithus.bot.core.loader;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Account discovered from the Jagex Launcher's local config.
 *
 * @param name     account display name
 * @param selected currently selected in launcher
 */
public record BwuProviderAccount(String name, boolean selected) {

    // name:     char[256]  offset 0
    // selected: i32        offset 256

    static BwuProviderAccount read(MemorySegment seg) {
        return new BwuProviderAccount(
                BwuLayouts.readString(seg, 0),
                seg.get(ValueLayout.JAVA_INT, 256) != 0
        );
    }
}
