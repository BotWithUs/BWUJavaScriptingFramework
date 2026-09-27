package com.botwithus.bot.cli.gui.pages.settings;

import java.util.List;
import java.util.OptionalInt;

/** One section as the page draws it this frame. */
public record SectionView(SettingsSection section, List<SettingsItem> items) {

    public SectionView {
        items = List.copyOf(items);
    }

    /** The count beside the section in the list on the left: the number of saved accounts. */
    public OptionalInt count() {
        for (SettingsItem item : items) {
            OptionalInt rows = switch (item) {
                case SettingsItem.Accounts accounts -> OptionalInt.of(accounts.rows().size());
                case SettingsItem.KeyRow _, SettingsItem.WaitPreview _, SettingsItem.PlaceRow _,
                     SettingsItem.ActionRow _, SettingsItem.RawKeys _ -> OptionalInt.empty();
            };
            if (rows.isPresent()) {
                return rows;
            }
        }
        return OptionalInt.empty();
    }
}
