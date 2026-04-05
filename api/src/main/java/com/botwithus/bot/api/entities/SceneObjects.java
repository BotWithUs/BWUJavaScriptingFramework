package com.botwithus.bot.api.entities;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.Entity;
import com.botwithus.bot.api.query.EntityType;

import java.util.List;
import java.util.function.Function;

/**
 * Query facade for scene object (location) entities. Provides convenience
 * methods and a fluent query builder that returns rich {@link SceneObject} wrappers.
 *
 * <h3>Quick usage:</h3>
 * <pre>{@code
 * SceneObjects objects = new SceneObjects(api);
 *
 * // Nearest object by name
 * SceneObject booth = objects.nearest("Bank booth");
 *
 * // All objects with a specific ID
 * List<SceneObject> rocks = objects.all(11360);
 *
 * // Fluent query
 * SceneObject door = objects.query()
 *     .named("Door")
 *     .visible()
 *     .withinDistance(10)
 *     .filter(o -> o.hasOption("Open"))
 *     .nearest();
 *
 * // Check transforms
 * SceneObject tree = objects.nearest("Tree");
 * if (tree != null && tree.canTransform()) {
 *     LocationType resolved = tree.resolveTransform();
 *     System.out.println("Resolved: " + resolved.name());
 * }
 * }</pre>
 *
 * @see SceneObject
 * @see EntityQuery
 */
public class SceneObjects {

    private final GameAPI api;

    public SceneObjects(GameAPI api) {
        this.api = api;
    }

    /**
     * Start a fluent scene object query.
     */
    public Query query() {
        return new Query(api);
    }

    /**
     * Returns the nearest scene object with the given name, or null.
     */
    public SceneObject nearest(String name) {
        return query().named(name).nearest();
    }

    /**
     * Returns the nearest scene object with the given type ID, or null.
     */
    public SceneObject nearest(int typeId) {
        return query().withId(typeId).nearest();
    }

    /**
     * Returns all scene objects matching the given name.
     */
    public List<SceneObject> all(String name) {
        return query().named(name).all();
    }

    /**
     * Returns all scene objects matching the given type ID.
     */
    public List<SceneObject> all(int typeId) {
        return query().withId(typeId).all();
    }

    /**
     * Fluent query builder for scene objects.
     */
    public static class Query extends EntityQuery<SceneObject, Query> {

        Query(GameAPI api) {
            super(api, EntityType.LOCATION);
        }

        @Override
        protected Function<Entity, SceneObject> wrapFunction() {
            return e -> new SceneObject(api, e);
        }
    }
}
