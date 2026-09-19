package com.botwithus.bot.api.model;

import java.util.List;

/**
 * Wire-format record returned by {@code query_locations} for one live
 * scene object. Pre-resolved fields ride on the response so the Java side
 * can answer position/distance queries without a per-object cache lookup;
 * {@link com.botwithus.bot.api.entities.SceneObject} additionally pulls
 * the full {@link LocationType} (size, varbits, params) on demand.
 *
 * @param handle      opaque server-side handle for action queueing
 * @param typeId      the loc id the server sent — what identity, hardcoded id sets and
 *                    interaction are keyed on
 * @param resolvedId  the loc id a definition lookup must use: {@code typeId} with the
 *                    morphvarp ("multiloc") transform applied by the producer. Equal to
 *                    {@code typeId} for a loc that does not morph. Use
 *                    {@link com.botwithus.bot.api.GameAPI#getLocationType getLocationType}
 *                    on <em>this</em> id — a morph loc's base definition has an empty name
 *                    and no options at all.
 * @param tileX       absolute world tile X
 * @param tileY       absolute world tile Y
 * @param plane       0..3
 * @param name        pre-resolved display name (may be empty)
 * @param options     pre-resolved right-click options (may be empty)
 */
public record SceneObjectInfo(
        int handle,
        int typeId,
        int resolvedId,
        int tileX,
        int tileY,
        int plane,
        String name,
        List<String> options
) {
    public SceneObjectInfo {
        options = List.copyOf(options);
    }
}
