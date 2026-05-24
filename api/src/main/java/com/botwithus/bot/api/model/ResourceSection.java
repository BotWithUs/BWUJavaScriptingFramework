package com.botwithus.bot.api.model;

import java.util.List;

/**
 * A group of {@link ResourceItem}s available at a {@link WorldMapElement},
 * grouped under a display title (e.g. "Coal rock", "Wisp", "Shark").
 *
 * @param title display title for this resource group
 * @param items individual resource lines; defensively copied
 */
public record ResourceSection(String title, List<ResourceItem> items) {
    public ResourceSection {
        items = List.copyOf(items);
    }
}
