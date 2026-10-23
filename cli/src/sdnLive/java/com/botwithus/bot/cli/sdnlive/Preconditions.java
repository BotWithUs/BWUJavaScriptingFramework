package com.botwithus.bot.cli.sdnlive;

import com.botwithus.bot.api.BotScript;
import com.botwithus.bot.core.crypto.SdnDiskBundleSource;
import com.botwithus.bot.core.crypto.SdnLoader;
import com.botwithus.bot.core.sdn.SdnRendezvous;

import java.nio.file.Path;

/**
 * What must hold before an install means anything. Each is a failure, never a
 * skip: a run that quietly lost one of these would pass while proving nothing.
 */
final class Preconditions {

    /** The debug runtime's override of the server key; the release runtime ignores it. */
    private static final String SERVER_PUBKEY_ENV = "BWU_SDN_SERVER_PUBKEY";

    private Preconditions() {
    }

    /** Records each precondition on {@code verdict}; true when they all hold. */
    static boolean hold(SdnLiveConfig config, Verdict verdict) {
        boolean modular = BotScript.class.getModule().isNamed();
        verdict.expect("the script API is a named module, as in the shipped host", modular,
                () -> "it is unnamed, so a delivered named module cannot resolve against it: "
                        + "this check is not running on the module path");
        boolean sdnRuntime = SdnLoader.isAvailable();
        verdict.expect("the runtime is an SDN runtime", sdnRuntime,
                () -> "jdk.internal.sdn.SdnClassLoader is absent: " + System.getProperty("java.home"));
        boolean delivery = SdnDiskBundleSource.isEnabled();
        verdict.expect("courier delivery is on", delivery, () -> "-D" + SdnDiskBundleSource.ENABLE_PROP + " is not true");
        boolean keyed = !System.getenv().getOrDefault(SERVER_PUBKEY_ENV, "").isBlank();
        verdict.expect("the runtime is told the run's signing key", keyed, () -> SERVER_PUBKEY_ENV + " is not set");
        boolean contained = isUnder(SdnRendezvous.directory(), config.workDir())
                && isUnder(Path.of(System.getProperty("user.home")), config.workDir());
        verdict.expect("the rendezvous directory and the home directory are this run's own", contained,
                () -> "rendezvous " + SdnRendezvous.directory() + ", home " + System.getProperty("user.home")
                        + ", run " + config.workDir() + ": refusing to touch a real launcher or ledger");
        return modular && sdnRuntime && delivery && keyed && contained;
    }

    private static boolean isUnder(Path path, Path root) {
        return path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }
}
