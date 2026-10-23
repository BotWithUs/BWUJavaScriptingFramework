package com.botwithus.bot.cli.gui.pages.groups;

import java.util.Objects;
import java.util.Optional;

/**
 * A member whose script a management script targets on its own, not only
 * through the group: the robot link under the member's account.
 *
 * @param managementScript the management script that names the member's script directly
 * @param label            "Own settings", "Also direct", or that script's name
 * @param tooltip          what the link means, in a sentence
 */
public record MemberManagement(String managementScript, String label, String tooltip) {

    /** The group's own manager also targets the member, with values of its own there. */
    public static final String OWN_SETTINGS = "Own settings";
    /** The group's own manager also targets the member, with no values of its own. */
    public static final String ALSO_DIRECT = "Also direct";

    public MemberManagement {
        Objects.requireNonNull(managementScript, "managementScript");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(tooltip, "tooltip");
    }

    /**
     * The link for a member whose {@code script} {@code direct} targets directly.
     *
     * @param groupManager the group's own manager, if it has one
     * @param ownSettings  how many values {@code direct} keeps for the member's script itself
     */
    public static MemberManagement of(Optional<String> groupManager, String direct, int ownSettings,
                                      String script) {
        boolean isGroupsManager = groupManager.filter(direct::equals).isPresent();
        String label = !isGroupsManager ? direct : ownSettings > 0 ? OWN_SETTINGS : ALSO_DIRECT;
        String tooltip = direct + " also targets this client's " + script + " directly"
                + (ownSettings > 0 ? ", with " + GroupText.count(ownSettings, "setting") + " of its own." : ".");
        return new MemberManagement(direct, label, tooltip);
    }
}
