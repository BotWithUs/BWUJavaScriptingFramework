package com.botwithus.bot.api.model;

import com.botwithus.bot.api.world.Coordinate;

/**
 * One transport or teleport a planned route takes, in route order: a door, stairs, a boat,
 * a fairy ring, a lodestone, a spell or item teleport.
 *
 * @param stepIndex       the route step that takes it
 * @param transitionIndex the pathfinder's index for it
 * @param rawKind         the pathfinder's kind code; see {@link #kind()}
 * @param moveCategory    the movement category bit number it falls under (doors 0,
 *                        shortcuts 1, plane changes 2, climb-overs 3, transports 4,
 *                        teleports 5, lodestones 6, fairy rings 7, spirit trees 8,
 *                        gliders 9, charters 10, magic carpets 11, other chains 12)
 * @param rawOrigin       what it is taken from; see {@link #origin()}
 * @param objectId        the loc id for a loc, the lowest NPC type id for an NPC, -1 for a
 *                        teleport cast from anywhere
 * @param from            where the player stands to take it: beside the loc or NPC, or
 *                        where a teleport is cast from
 * @param to              where it lands
 * @param code            the fairy-ring code, empty otherwise
 * @param name            the dataset name of a runtime-loaded teleport (lodestones, spells,
 *                        items); empty for every baked transport, which have no names
 */
public record RouteLink(int stepIndex, long transitionIndex, int rawKind, int moveCategory,
                        int rawOrigin, int objectId, Coordinate from, Coordinate to,
                        String code, String name) {

    /** The decoded kind. */
    public Kind kind() {
        return Kind.fromWireCode(rawKind);
    }

    /** The decoded origin. */
    public Origin origin() {
        return Origin.fromWireCode(rawOrigin);
    }

    /** What sort of link it is ({@code WW_TRANSITION_KIND_*}). */
    public enum Kind {
        TRANSPORT(0), FAIRY_RING(1), TELEPORT_CHAIN(2), SPELL(3), LODESTONE(4), ITEM_TELEPORT(5),
        /** A code this host does not list. */
        UNKNOWN(-1);

        private final int wireCode;

        Kind(int wireCode) {
            this.wireCode = wireCode;
        }

        /** The kind for a pathfinder code; {@link #UNKNOWN} for anything unlisted. */
        public static Kind fromWireCode(int code) {
            for (Kind k : values()) {
                if (k != UNKNOWN && k.wireCode == code) {
                    return k;
                }
            }
            return UNKNOWN;
        }
    }

    /** What a link is taken from ({@code WW_TRANSITION_ORIGIN_*}). */
    public enum Origin {
        LOC(0), NPC(1),
        /** Cast from anywhere: a spell, item or lodestone teleport. */
        GLOBAL(2),
        /** A code this host does not list. */
        UNKNOWN(-1);

        private final int wireCode;

        Origin(int wireCode) {
            this.wireCode = wireCode;
        }

        /** The origin for a pathfinder code; {@link #UNKNOWN} for anything unlisted. */
        public static Origin fromWireCode(int code) {
            for (Origin o : values()) {
                if (o != UNKNOWN && o.wireCode == code) {
                    return o;
                }
            }
            return UNKNOWN;
        }
    }
}
