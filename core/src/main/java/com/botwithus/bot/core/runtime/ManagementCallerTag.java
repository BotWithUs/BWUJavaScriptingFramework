package com.botwithus.bot.core.runtime;

/**
 * Says, for the calling thread, which management script it belongs to, so that
 * per-connection resources a management script makes can be attributed to it and
 * taken back when it stops.
 *
 * <p>Management script threads are deliberately left out of every connection's
 * {@link ScriptGate}: a management script is cross-client, its walks run with host
 * authority, and no one gate could revoke it. That made them indistinguishable from
 * host code, which is wrong for plugin UI: their components would never be
 * released, and they could list and destroy every other script's components. This
 * tag is consulted <em>only</em> for plugin-UI ownership, so walks and revocation
 * keep their existing semantics.</p>
 *
 * <p>One instance per process, created at the composition root and handed to both
 * the management runtime (which tags) and every connection's {@code GameAPIImpl}
 * (which reads), because one management thread acts on many connections. An
 * instance, not a static holder, for the same reason as {@link ScriptGate}. The
 * tag is inheritable, so threads a management script spawns carry it too.</p>
 */
public final class ManagementCallerTag {

    private static final String OWNER_PREFIX = "<mgmt:";
    private static final String OWNER_SUFFIX = ">";

    private final InheritableThreadLocal<String> current = new InheritableThreadLocal<>();

    public ManagementCallerTag() {
    }

    /**
     * The owner name a management script's resources are recorded under. Angle
     * brackets keep it out of the namespace of script names, as the host's own
     * owner is, so a script can never be mistaken for a management script.
     */
    public static String ownerOf(String managementScriptName) {
        return OWNER_PREFIX + managementScriptName + OWNER_SUFFIX;
    }

    /** Whether {@code owner} is a name {@link #ownerOf} made. */
    public static boolean isOwnerName(String owner) {
        return owner != null && owner.startsWith(OWNER_PREFIX) && owner.endsWith(OWNER_SUFFIX);
    }

    /** Tags the calling thread, and any thread it later spawns, as {@code owner}. */
    public void enter(String owner) {
        current.set(owner);
    }

    /** Clears the calling thread's tag. */
    public void exit() {
        current.remove();
    }

    /** The owner the calling thread belongs to, or {@code null} for anything but a management script. */
    public String current() {
        return current.get();
    }
}
