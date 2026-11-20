package com.botwithus.bot.api.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The tiles inside a polygon whose vertices are tiles, on one plane. A tile on an edge or a
 * vertex counts as inside, so a thin or degenerate polygon still contains its outline.
 * Inside-ness off the outline follows the even-odd rule.
 *
 * @param vertices the corners in order, all on one plane; the last joins the first
 */
public record PolygonalArea(List<Coordinate> vertices) implements Area {

    /** Fewest vertices a polygon can have. */
    public static final int MIN_VERTICES = 3;

    /**
     * Random draws from the bounding box {@link #randomTile} makes before it falls back to
     * listing the polygon's tiles, which a very thin polygon would otherwise need many of.
     */
    private static final int MAX_REJECTION_DRAWS = 64;

    /**
     * @throws IllegalArgumentException with fewer than {@value #MIN_VERTICES} vertices, or
     *                                  vertices on more than one plane
     */
    public PolygonalArea {
        if (vertices.size() < MIN_VERTICES) {
            throw new IllegalArgumentException("a polygon needs at least " + MIN_VERTICES
                    + " vertices, got " + vertices.size());
        }
        vertices = List.copyOf(vertices);
        boundsOf(vertices);
    }

    /** {@link #PolygonalArea(List)} from varargs. */
    public static PolygonalArea of(Coordinate... vertices) {
        return new PolygonalArea(List.of(vertices));
    }

    /** The smallest rectangle holding every tile of the polygon. */
    public RectangularArea bounds() {
        return boundsOf(vertices);
    }

    /** {@link #bounds()}. */
    @Override
    public Optional<RectangularArea> boundingBox() {
        return Optional.of(bounds());
    }

    @Override
    public int plane() {
        return vertices.getFirst().plane();
    }

    @Override
    public boolean contains(int x, int y, int tilePlane) {
        if (!bounds().contains(x, y, tilePlane)) {
            return false;
        }
        return isOnOutline(x, y) || isInsideEvenOdd(x, y);
    }

    @Override
    public Coordinate randomTile(RandomGenerator random) {
        RectangularArea bounds = bounds();
        for (int i = 0; i < MAX_REJECTION_DRAWS; i++) {
            Coordinate tile = bounds.randomTile(random);
            if (contains(tile)) {
                return tile;
            }
        }
        List<Coordinate> tiles = tiles();
        return tiles.get(random.nextInt(tiles.size()));
    }

    /** Every tile of the polygon, row by row. Never empty: the vertices are always inside. */
    public List<Coordinate> tiles() {
        RectangularArea bounds = bounds();
        List<Coordinate> tiles = new ArrayList<>();
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                if (contains(x, y, plane())) {
                    tiles.add(new Coordinate(x, y, plane()));
                }
            }
        }
        return tiles;
    }

    private boolean isOnOutline(int x, int y) {
        for (int i = 0; i < vertices.size(); i++) {
            Coordinate a = vertices.get(i);
            Coordinate b = vertices.get((i + 1) % vertices.size());
            if (isOnSegment(x, y, a, b)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOnSegment(int x, int y, Coordinate a, Coordinate b) {
        long cross = (long) (b.x() - a.x()) * (y - a.y()) - (long) (b.y() - a.y()) * (x - a.x());
        return cross == 0
                && x >= Math.min(a.x(), b.x()) && x <= Math.max(a.x(), b.x())
                && y >= Math.min(a.y(), b.y()) && y <= Math.max(a.y(), b.y());
    }

    /** Even-odd ray cast towards +x. */
    private boolean isInsideEvenOdd(int x, int y) {
        boolean isInside = false;
        for (int i = 0, j = vertices.size() - 1; i < vertices.size(); j = i++) {
            Coordinate a = vertices.get(i);
            Coordinate b = vertices.get(j);
            if ((a.y() > y) != (b.y() > y)) {
                double crossX = a.x() + (double) (y - a.y()) * (b.x() - a.x()) / (b.y() - a.y());
                if (x < crossX) {
                    isInside = !isInside;
                }
            }
        }
        return isInside;
    }

    private static RectangularArea boundsOf(List<Coordinate> vertices) {
        Coordinate first = vertices.getFirst();
        RectangularArea box = RectangularArea.of(first, first);
        for (Coordinate v : vertices) {
            if (v.plane() != first.plane()) {
                throw new IllegalArgumentException("vertices span planes " + first.plane()
                        + " and " + v.plane());
            }
            box = RectangularArea.of(
                    new Coordinate(Math.min(box.minX(), v.x()), Math.min(box.minY(), v.y()), v.plane()),
                    new Coordinate(Math.max(box.maxX(), v.x()), Math.max(box.maxY(), v.y()), v.plane()));
        }
        return box;
    }
}
