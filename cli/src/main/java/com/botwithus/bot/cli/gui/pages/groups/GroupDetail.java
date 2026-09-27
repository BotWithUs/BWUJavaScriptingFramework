package com.botwithus.bot.cli.gui.pages.groups;

import com.botwithus.bot.cli.groups.ClientGroup;

import java.util.List;
import java.util.Objects;

/**
 * The selected group, as the right-hand side of the page draws it.
 *
 * @param rows members in member order, then unresolved members
 */
public record GroupDetail(ClientGroup group, GroupSummary summary, List<MemberRow> rows) {

    public GroupDetail {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(summary, "summary");
        rows = List.copyOf(rows);
    }

    /** Every row's selection key, in row order. */
    public List<String> keys() {
        return rows.stream().map(MemberRow::key).toList();
    }
}
