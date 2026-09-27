package com.botwithus.bot.cli.management;

import com.botwithus.bot.cli.management.ManagementTargets.ManagedBy;

import java.util.List;
import java.util.Optional;

/**
 * Which management scripts a client script links to where it is shown: a
 * card, a group member, an installed script. Only the ones that name it
 * directly or by its group count. A script that manages the whole host
 * covers every client script there is, so a link on each of them would say
 * nothing; the Management page lists those.
 */
public final class ManagedLinks {

    private ManagedLinks() {}

    /** The management scripts that name {@code scriptName} on account {@code accountUuid}, or its group. */
    public static List<ManagedBy> of(ManagementTargets targets, String accountUuid, String scriptName) {
        return targets.managedBy(accountUuid, scriptName).stream()
                .filter(by -> switch (by.via()) {
                    case Target.Host _ -> false;
                    case Target.Group _, Target.ClientScript _ -> true;
                })
                .toList();
    }

    /** The one management script a row shows: the most specific of {@link #of}. */
    public static Optional<String> first(ManagementTargets targets, String accountUuid, String scriptName) {
        return of(targets, accountUuid, scriptName).stream().findFirst().map(ManagedBy::managementScript);
    }

    /** The names of every management script in {@link #of}, most specific first. */
    public static List<String> names(ManagementTargets targets, String accountUuid, String scriptName) {
        return of(targets, accountUuid, scriptName).stream().map(ManagedBy::managementScript).toList();
    }
}
