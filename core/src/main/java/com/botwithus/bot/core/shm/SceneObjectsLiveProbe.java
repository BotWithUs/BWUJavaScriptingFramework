package com.botwithus.bot.core.shm;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.model.LocationType;
import com.botwithus.bot.core.cache.NXTCache;
import com.botwithus.bot.core.impl.snapshot.GameSnapshotImpl;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Standalone smoke test for the host-side scene-object query path. Exercises
 * the exact chain {@code WoodcuttingFletcherScript} uses:
 *
 * <pre>{@code
 *   api.objects().query().namedExact("Tree").withinDistance(N).nearest()
 * }</pre>
 *
 * <p>Hits the v15+ SHM-backed source ({@link SceneObjects.Query#source()})
 * and the {@link NXTCache}-backed {@code getLocationType} for name resolution.
 * No RPC, no script lifecycle — just the bare data path.</p>
 *
 * <pre>{@code
 *   java -cp <classpath> -Dnxtcache.dll=<path> com.botwithus.bot.core.shm.SceneObjectsLiveProbe <pid> [radius] [name]
 * }</pre>
 *
 * <p>Defaults: radius=8, name="Tree".</p>
 */
public final class SceneObjectsLiveProbe {

    private SceneObjectsLiveProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: SceneObjectsLiveProbe <pid> [radius] [name]");
            System.exit(2);
        }
        long pid = Long.parseLong(args[0]);
        int radius = args.length >= 2 ? Integer.parseInt(args[1]) : 8;
        String wanted = args.length >= 3 ? args[2] : "Tree";

        try (SharedRegion region = SharedRegion.open(pid);
             NXTCache cache = NXTCache.tryOpenFromSystemProperty()) {

            if (cache == null) {
                System.err.println("NXTCache not loaded — set -Dnxtcache.dll=<path>");
                System.exit(3);
            }

            GameAPI api = makeProxy(region, cache);
            SceneObjects objects = new SceneObjects(api);

            GameSnapshot snap = api.snapshot();
            LocalPlayer self = snap.self();
            System.out.printf("tick=%d state=%d self=(%d,%d,%d) locsTotal=%d%n",
                    snap.serverTick(), snap.gameState(),
                    self == null ? -1 : self.tileX(),
                    self == null ? -1 : self.tileY(),
                    self == null ? -1 : self.plane(),
                    snap.locations().count());

            // First, list everything within radius that survives the
            // resolveLocHandle filter — sanity check before naming.
            List<SceneObject> nearby = objects.query().withinDistance(radius).all();
            System.out.printf("scene objects within %d tiles: %d%n", radius, nearby.size());
            int n = 0;
            int cacheMiss = 0;
            int emptyName = 0;
            for (SceneObject o : nearby) {
                LocationType t = o.getType();
                if (t == null) {
                    cacheMiss++;
                } else if (t.name() == null || t.name().isEmpty()) {
                    emptyName++;
                }
                if (n++ < 14) {
                    System.out.printf("  id=%d @(%d,%d,%d) typeFound=%s name=\"%s\" opts=%s%n",
                            o.typeId(), o.tileX(), o.tileY(), o.plane(),
                            t == null ? "NO" : "yes",
                            t == null ? "" : t.name(),
                            t == null ? "[]" : t.options());
                }
            }
            if (nearby.size() > 14) System.out.println("  ...");
            System.out.printf("of %d nearby: %d cache-miss (cache.getLocation returned null), %d named=\"\"%n",
                    nearby.size(), cacheMiss, emptyName);

            // Anything in the whole loaded scene whose name contains the
            // search term (case-insensitive). Helps locate the target when
            // the player isn't standing next to one.
            String needle = wanted.toLowerCase();
            List<SceneObject> matches = objects.query().all().stream()
                    .filter(o -> {
                        String nm = o.name();
                        return nm != null && nm.toLowerCase().contains(needle);
                    }).toList();
            System.out.printf("anywhere in scene matching \"%s\": %d%n", wanted, matches.size());
            int m = 0;
            for (SceneObject o : matches) {
                if (m++ >= 12) { System.out.println("  ..."); break; }
                System.out.printf("  id=%d @(%d,%d,%d) name=\"%s\"%n",
                        o.typeId(), o.tileX(), o.tileY(), o.plane(), o.name());
            }

            // Now the exact chain from WoodcuttingFletcherScript:
            SceneObject hit = objects.query()
                    .namedExact(wanted)
                    .withinDistance(radius)
                    .nearest();
            System.out.printf("%nnamedExact(\"%s\").withinDistance(%d).nearest() => ", wanted, radius);
            if (hit == null) {
                System.out.println("null (no match)");
            } else {
                LocationType type = hit.getType();
                System.out.printf("id=%d name=\"%s\" @(%d,%d,%d) options=%s%n",
                        hit.typeId(), hit.name(),
                        hit.tileX(), hit.tileY(), hit.plane(),
                        type == null ? "<null type>" : type.options());
            }
        }
    }

    private static GameAPI makeProxy(SharedRegion region, NXTCache cache) {
        InvocationHandler h = (proxy, method, methodArgs) -> {
            switch (method.getName()) {
                case "snapshot":
                    return new GameSnapshotImpl(region.snapshot());
                case "getLocalPlayer":
                    return new GameSnapshotImpl(region.snapshot()).self();
                case "getLocationType":
                    return cache.getLocation((int) methodArgs[0]);
                case "getItemType":
                    return cache.getItem((int) methodArgs[0]);
                case "getNpcType":
                    return cache.getNpc((int) methodArgs[0]);
                default:
                    throw new UnsupportedOperationException(
                            "Live probe only implements snapshot/getLocalPlayer/get*Type — got " + method.getName());
            }
        };
        return (GameAPI) Proxy.newProxyInstance(
                GameAPI.class.getClassLoader(),
                new Class<?>[]{GameAPI.class},
                h);
    }
}
