package com.botwithus.bot.cli.gui.inspector;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.gameval.GamevalType;
import com.botwithus.bot.api.model.ItemType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The name shown under an item-id field. The item's gameval (its stable symbolic
 * name, {@code YEW_LOGS}) comes first, because that is what a script author
 * writes; the cache's display name ("Yew logs") stands in when no gameval index
 * is deployed. Both come from the host's own in-process readers, never the pipe.
 *
 * <p>Answers are memoised, misses included, but only once a client has been
 * asked: with nothing connected there is nobody to ask yet, and remembering that
 * as "no name" would outlive the first connection. Render thread only.</p>
 */
final class ItemNames {

    private static final Logger log = LoggerFactory.getLogger(ItemNames.class);

    /** What the cache reports for an item that has no name. */
    private static final String NO_ITEM_NAME = "null";

    private final Supplier<Optional<GameAPI>> api;
    private final Map<Integer, Optional<String>> names = new HashMap<>();

    /** @param api a connected client to ask, or empty when none is connected */
    ItemNames(Supplier<Optional<GameAPI>> api) {
        this.api = api;
    }

    Optional<String> of(int id) {
        Optional<String> known = names.get(id);
        if (known != null) {
            return known;
        }
        Optional<GameAPI> client = api.get();
        if (client.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> found = lookup(client.get(), id);
        names.put(id, found);
        return found;
    }

    private static Optional<String> lookup(GameAPI api, int id) {
        try {
            Optional<String> gameval = api.gamevals().gameval(GamevalType.ITEM, id);
            return gameval.isPresent() ? gameval : displayName(api.getItemType(id));
        } catch (RuntimeException e) {
            log.debug("Item name lookup for {} failed: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<String> displayName(ItemType type) {
        if (type == null || type.name() == null || type.name().isBlank() || NO_ITEM_NAME.equals(type.name())) {
            return Optional.empty();
        }
        return Optional.of(type.name());
    }
}
