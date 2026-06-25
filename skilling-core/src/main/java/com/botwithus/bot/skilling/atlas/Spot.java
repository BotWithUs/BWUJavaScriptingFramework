package com.botwithus.bot.skilling.atlas;

/**
 * One placement of a gather/station resource from the Atlas {@code gather} table:
 * a world tile plus the loc it belongs to and (where the skilling data table knew
 * it) the level requirement and xp the resource yields.
 *
 * @param item     resource item id this spot yields ({@code 0} for non-item
 *                 stations like banks)
 * @param category gather category ({@code "woodcutting"}, {@code "mining"},
 *                 {@code "bank"}, ...)
 * @param skill    skill name, or {@code null} for non-skill stations
 * @param kind     {@code "skilling"} / {@code "station"} / ...
 * @param loc      loc (SceneObject) type id of the resource — match scene objects
 *                 against this
 * @param locName  display name of the loc, or {@code null}
 * @param x        world tile X
 * @param y        world tile Y
 * @param plane    plane
 * @param level    skill level required to gather here, or {@code null} if unknown
 * @param xp       xp per gather, or {@code null} if unknown
 */
public record Spot(int item, String category, String skill, String kind,
                   int loc, String locName, int x, int y, int plane,
                   Integer level, Double xp) {

    public Tile tile() {
        return new Tile(x, y, plane);
    }
}
