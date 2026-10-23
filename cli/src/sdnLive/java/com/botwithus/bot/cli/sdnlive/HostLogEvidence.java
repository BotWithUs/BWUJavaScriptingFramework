package com.botwithus.bot.cli.sdnlive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * What this process's own log must, and must not, say once the run is over.
 *
 * <p>The screens' state says what the user would see; the log says why. The
 * negative control's cause in particular is visible only here: from the screens
 * a refused signature and a courier that never answered look alike.
 */
final class HostLogEvidence {

    private static final String SIGNATURE_REFUSED = "key envelope signature invalid";
    /** The picker's old failure text, retired on master but checked so it cannot come back unseen. */
    private static final Pattern OLD_UNPAIRED = Pattern.compile("delivered \\d+ script\\(s\\), but not ");
    private static final String UNPAIRED = "but none could be matched to";

    private HostLogEvidence() {
    }

    static void check(Path hostLog, SdnLiveCases cases, Verdict verdict) throws IOException {
        String text = Files.readString(hostLog, StandardCharsets.UTF_8);
        verdict.expect("log: the negative control was refused for its signature", text.contains(SIGNATURE_REFUSED),
                () -> "no \"" + SIGNATURE_REFUSED + "\" in " + hostLog);
        verdict.expect("log: no delivery failed to pair with its entry",
                !OLD_UNPAIRED.matcher(text).find() && !text.contains(UNPAIRED),
                () -> "a pairing failure is in " + hostLog);
        for (SdnLiveCase c : cases.positives()) {
            String loaded = "SDN: loaded " + c.deliveredClass() + " from module " + c.moduleName();
            verdict.expect("log: " + c.id() + " loaded from its module", text.contains(loaded),
                    () -> "no \"" + loaded + "\"");
            String ran = "SDN-LIVE fixture " + c.deliveredClass() + " started in module " + c.moduleName()
                    + " (named=true)";
            verdict.expect("log: " + c.id() + " ran, and said so from inside its module", text.contains(ran),
                    () -> "no \"" + ran + "\"");
        }
    }
}
