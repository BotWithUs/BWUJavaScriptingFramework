package com.botwithus.bot.cli.gui.inspector;

import java.util.Optional;

/**
 * Finds the script an {@link InspectorSubject} names. The live source reads the
 * host's runners; the dev preview supplies fixtures through the same seam.
 * Called on the render thread every frame the inspector is open, so it must be
 * cheap and must not block.
 */
@FunctionalInterface
public interface InspectorSource {

    /** The subject's script as it is now, or empty when no such runner exists. */
    Optional<InspectorTarget> resolve(InspectorSubject subject);
}
