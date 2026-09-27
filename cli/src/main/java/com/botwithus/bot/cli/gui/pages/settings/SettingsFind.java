package com.botwithus.bot.cli.gui.pages.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * "Find a setting": narrows the page to what matches the words typed. A row is
 * kept when its label, description or key contains the query; a whole section
 * is kept when its title does; the accounts and config-key tables and the
 * Integrations event grid are narrowed row by row. An Integrations card is kept
 * whole when its service, a field or one of its keys matches. Sections with
 * nothing left are dropped, which the section list shows by dimming them.
 */
public final class SettingsFind {

    private SettingsFind() {
    }

    /** The sections to draw for {@code query}; all of them, unchanged, when it is blank. */
    public static List<SectionView> filter(List<SectionView> sections, String query) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return sections;
        }
        List<SectionView> kept = new ArrayList<>();
        for (SectionView section : sections) {
            if (titleMatches(section.section(), q)) {
                kept.add(section);
                continue;
            }
            List<SettingsItem> items = new ArrayList<>();
            for (SettingsItem item : section.items()) {
                narrow(item, q).ifPresent(items::add);
            }
            if (!items.isEmpty()) {
                kept.add(new SectionView(section.section(), items));
            }
        }
        return kept;
    }

    private static boolean titleMatches(SettingsSection section, String q) {
        return section.title().toLowerCase(Locale.ROOT).contains(q);
    }

    /** The item as it shows for {@code q}: whole, narrowed to its matching rows, or gone. */
    private static Optional<SettingsItem> narrow(SettingsItem item, String q) {
        if (item.findText().contains(q)) {
            return Optional.of(item);
        }
        return switch (item) {
            case SettingsItem.Accounts accounts -> {
                List<AccountRow> rows = accounts.rows().stream().filter(r -> r.findText().contains(q)).toList();
                yield rows.isEmpty() ? Optional.empty() : Optional.of(new SettingsItem.Accounts(rows));
            }
            case SettingsItem.RawKeys keys -> {
                List<RawKeyRow> rows = keys.rows().stream().filter(r -> r.findText().contains(q)).toList();
                yield rows.isEmpty() ? Optional.empty() : Optional.of(new SettingsItem.RawKeys(rows));
            }
            case SettingsItem.EventGrid grid -> {
                List<GridRow> rows = grid.rows().stream().filter(r -> r.findText().contains(q)).toList();
                yield rows.isEmpty() ? Optional.empty() : Optional.of(new SettingsItem.EventGrid(grid.columns(), rows));
            }
            case SettingsItem.KeyRow _, SettingsItem.WaitPreview _, SettingsItem.PlaceRow _,
                 SettingsItem.ActionRow _, SettingsItem.ServiceCard _, SettingsItem.QuietHours _,
                 SettingsItem.Notice _ -> Optional.empty();
        };
    }
}
