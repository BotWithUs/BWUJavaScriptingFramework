package com.botwithus.bot.api.domain;

import com.botwithus.bot.api.model.ComponentChild;
import com.botwithus.bot.api.model.GeOffer;
import com.botwithus.bot.api.model.TargetMode;
import com.botwithus.bot.api.model.WorldToScreen;
import com.botwithus.bot.api.world.Coordinate;
import com.botwithus.bot.api.world.WorldHopRequest;
import com.botwithus.bot.api.world.WorldList;

import java.util.List;

/**
 * Reads of state the client already holds, and the world change, one RPC each. Every read
 * runs on the game thread and asks the game to fetch nothing.
 *
 * <p>These are the wire-shaped calls. Most scripts go through the facades built on them:
 * {@code api.worlds()} for the world list and hopping, {@code GrandExchange.offers()},
 * {@code api.backpack().useOn(...)} for target mode.</p>
 *
 * <p>An agent that predates a method refuses it with an unknown-method error, which
 * surfaces as the transport's exception.</p>
 */
public interface ClientStateAPI {

    /** {@code get_current_world}: the world number, {@code -1} when unknown or between worlds. */
    int getCurrentWorld();

    /**
     * {@code get_world_list}: the world-select list as the client holds it. Empty (not an
     * error) until the game has fetched it, which every login through the lobby does.
     */
    WorldList getWorldList();

    /**
     * {@code set_world}: asks the agent to change world through the game's own UI. Returns
     * as soon as the clicks are queued or refused; it does not wait for the change. Use
     * {@code api.worlds().hop(id)} to wait for arrival.
     *
     * @throws IllegalArgumentException for {@code worldId <= 0}, which the agent refuses
     */
    WorldHopRequest requestWorld(int worldId);

    /**
     * {@code get_ge_offers} for the main market: the eight Grand Exchange slots, in slot
     * order. A slot holds an offer only when {@link GeOffer#isActive()}.
     */
    List<GeOffer> getGeOffers();

    /** {@code get_target_mode}: what, if anything, is selected for "Use X on Y". */
    TargetMode getTargetMode();

    /**
     * {@code world_to_screen}: projects tile centres to window client pixels, with the
     * projector the agent's debug drawing uses. A tile is placed at the local player's
     * height, so one on another plane comes back
     * {@link com.botwithus.bot.api.model.ScreenProjection.Result#WRONG_PLANE}.
     *
     * @param tiles 1 to {@link WorldToScreen#MAX_POINTS} tiles
     * @throws IllegalArgumentException for an empty or over-size list
     */
    WorldToScreen worldToScreen(List<Coordinate> tiles);

    /**
     * {@code get_static_children} with {@code withSub: true}: each static child with its
     * sub id ({@code -1}, static children have none).
     */
    List<ComponentChild> getStaticChildSlots(int interfaceId, int componentId);

    /**
     * {@code get_dynamic_children} with {@code withSub: true}: each dynamic child with the
     * sub id that tells it apart. Every entry's component id is the parent's; the sub id is
     * what a click on the child sends.
     */
    List<ComponentChild> getDynamicChildSlots(int interfaceId, int componentId);
}
