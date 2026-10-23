package com.botwithus.bot.cli.sdnlive;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The entries one run installs: two shapes, each installed once through each
 * screen, plus the negative control.
 *
 * <p>Every entry gets a jar of its own. A runtime keys runners by name and the
 * host pairs a script with its entry by the jar it came in, so two entries
 * sharing a jar would make each case depend on the order the others ran in.
 *
 * @param craftFromStore  CraftWithUs-shaped, installed from the Store page
 * @param blankFromStore  blank class, installed from the Store page, in the same batch
 * @param craftFromPicker CraftWithUs-shaped, installed and started from the picker
 * @param blankFromPicker blank class, installed and started from the picker
 * @param negative        delivered under the wrong signing key; must never load
 */
record SdnLiveCases(SdnLiveCase craftFromStore, SdnLiveCase blankFromStore, SdnLiveCase craftFromPicker,
                    SdnLiveCase blankFromPicker, SdnLiveCase negative) {

    /**
     * What the catalogue names a CraftWithUs-shaped entry as: an agent-v1 class
     * absent from the v2 jar actually delivered, as the real entry is.
     */
    private static final String AGENT_V1_PACKAGE = "com.botwithus.sdnlive.agentv1.";
    private static final String BLANK_CLASS = "";
    private static final String ID_PREFIX = "sdn-live-";

    /** The cases over the fixture jars, keyed by variant. */
    static SdnLiveCases over(Map<String, Path> jarsByVariant) {
        return new SdnLiveCases(
                craftShaped("craftstore", jarsByVariant),
                blank("blankstore", jarsByVariant),
                craftShaped("craftpicker", jarsByVariant),
                blank("blankpicker", jarsByVariant),
                // Its jar is never defined, so sharing one cannot leak into another case.
                new SdnLiveCase(ID_PREFIX + "negative", "craftstore", AGENT_V1_PACKAGE + "Negative",
                        jar("craftstore", jarsByVariant)));
    }

    /** Every entry, in catalogue order. */
    List<SdnLiveCase> all() {
        List<SdnLiveCase> all = new ArrayList<>(positives());
        all.add(negative);
        return all;
    }

    /** The entries that must install and start. */
    List<SdnLiveCase> positives() {
        return List.of(craftFromStore, blankFromStore, craftFromPicker, blankFromPicker);
    }

    /** What the courier delivers for each id. */
    Map<String, Path> jarsById() {
        Map<String, Path> byId = new LinkedHashMap<>();
        all().forEach(c -> byId.put(c.id(), c.jar()));
        return byId;
    }

    private static SdnLiveCase craftShaped(String variant, Map<String, Path> jars) {
        return new SdnLiveCase(ID_PREFIX + variant, variant, AGENT_V1_PACKAGE + "CraftWithUs" + variant,
                jar(variant, jars));
    }

    private static SdnLiveCase blank(String variant, Map<String, Path> jars) {
        return new SdnLiveCase(ID_PREFIX + variant, variant, BLANK_CLASS, jar(variant, jars));
    }

    private static Path jar(String variant, Map<String, Path> jars) {
        Path jar = jars.get(variant);
        if (jar == null) {
            throw new IllegalArgumentException("no fixture jar for variant " + variant + "; have " + jars.keySet());
        }
        return jar;
    }
}
