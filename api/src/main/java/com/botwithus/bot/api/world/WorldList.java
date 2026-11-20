package com.botwithus.bot.api.world;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The client's world-select list ({@code get_world_list}).
 *
 * <p>An empty list is not an error: the client fetches the list for the lobby or the world
 * switcher, so it is empty until then. {@link #isFetched()} is the predicate for that.</p>
 *
 * @param worlds    the readable worlds, in the client's order
 * @param total     the client's list length
 * @param skipped   entries the agent could not read
 * @param truncated true when the list was cut (past 512 entries)
 */
public record WorldList(List<World> worlds, int total, int skipped, boolean truncated) {

    /** What a client that has not fetched the list reports. */
    public static final WorldList NOT_FETCHED = new WorldList(List.of(), 0, 0, false);

    public WorldList {
        worlds = List.copyOf(worlds);
    }

    /** False until the game has fetched the list. */
    public boolean isFetched() {
        return !worlds.isEmpty();
    }

    /** The world numbered {@code id}, if listed. */
    public Optional<World> find(int id) {
        return worlds.stream().filter(w -> w.id() == id).findFirst();
    }

    /** The worlds {@code filter} accepts, in list order. */
    public List<World> filter(Predicate<World> filter) {
        return worlds.stream().filter(filter).toList();
    }
}
