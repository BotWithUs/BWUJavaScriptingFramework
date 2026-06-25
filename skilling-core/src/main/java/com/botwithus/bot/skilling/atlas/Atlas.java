package com.botwithus.bot.skilling.atlas;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Read-only reader over the baked Atlas ({@code resolved.sqlite}) — the spatial /
 * inverse / dependency data layer the live cache can't answer: where a resource
 * is, what produces an item, and the full recipe dependency closure.
 *
 * <p>Forward, id-keyed definition lookups (item/npc/loc) stay live on
 * {@code NXTCache.dll}; this reader only owns the baked layers. Open once and
 * reuse — a single {@link Connection} is held for the lifetime of the instance;
 * {@link #close()} releases it. Not safe for concurrent use across threads (the
 * underlying JDBC connection is single-threaded); a skill script opens its own.</p>
 *
 * @see com.botwithus.bot.skilling.plan.Planner
 */
public final class Atlas implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Atlas.class);
    private static final int MAX_CLOSURE_NODES = 6000;

    private final Connection con;

    private Atlas(Connection con) {
        this.con = con;
    }

    /** Open the Atlas from its default location, or empty when no file is present. */
    public static Optional<Atlas> openDefault() {
        return AtlasPaths.locate().map(Atlas::open);
    }

    /** Open a specific Atlas file read-only. Throws {@link AtlasException} on failure. */
    public static Atlas open(Path dbFile) {
        try {
            SQLiteConfig cfg = new SQLiteConfig();
            cfg.setReadOnly(true);
            SQLiteDataSource ds = new SQLiteDataSource(cfg);
            ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
            return new Atlas(ds.getConnection());
        } catch (SQLException e) {
            throw new AtlasException("failed to open Atlas at " + dbFile, e);
        }
    }

    @Override
    public void close() {
        try {
            con.close();
        } catch (SQLException e) {
            log.debug("Atlas close failed", e);
        }
    }

    // ------------------------------------------------------------------ meta

    /** A value from the {@code meta} table (build counts/timestamp), or empty. */
    public Optional<String> meta(String key) {
        try (PreparedStatement ps = con.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new AtlasException("meta(" + key + ")", e);
        }
    }

    // ------------------------------------------------------- named components

    /**
     * Resolve a UI component from its gameval symbolic name (the index-67
     * {@code component} group), e.g. {@code "BANK__BANK_INV_BUTTON"} → interface
     * 517, component 39. Empty when the name isn't in the Atlas. The packed
     * gameval id splits as {@code interfaceId = id >> 16}, {@code componentId =
     * id & 0xFFFF}.
     */
    public Optional<NamedComponent> component(String gameval) {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT eid FROM node WHERE etype = 'component' AND gameval = ? LIMIT 1")) {
            ps.setString(1, gameval);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long eid = rs.getLong(1);
                    return Optional.of(new NamedComponent(gameval,
                            (int) (eid >> 16), (int) (eid & 0xFFFF)));
                }
            }
        } catch (SQLException e) {
            throw new AtlasException("component(" + gameval + ")", e);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------- gameval resolvers

    /**
     * Resolve any entity id from its gameval symbolic name within an etype group
     * (the {@code node} table is keyed {@code uid = "<etype>:<eid>"} and carries
     * the gameval). Lets a script look up items / varps / structs by their stable
     * symbolic name rather than hardcoding ids — a game renumber is then fixed by
     * rebuilding the Atlas, not by editing code. Empty when the name is absent.
     *
     * @param etype   the node etype group, e.g. {@code "item"}, {@code "varp"},
     *                {@code "struct"}, {@code "loc"}, {@code "npc"}, {@code "enum"}
     * @param gameval the symbolic name, e.g. {@code "WOODCUTTING_WOODBOX_BASIC"}
     */
    public OptionalInt gamevalId(String etype, String gameval) {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT eid FROM node WHERE etype = ? AND gameval = ? LIMIT 1")) {
            ps.setString(1, etype);
            ps.setString(2, gameval);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return OptionalInt.of(rs.getInt(1));
                }
            }
        } catch (SQLException e) {
            throw new AtlasException("gamevalId(" + etype + "," + gameval + ")", e);
        }
        return OptionalInt.empty();
    }

    /** An item id by gameval name (e.g. {@code "WOODCUTTING_WOODBOX_BASIC"} → 54895). */
    public OptionalInt itemId(String gameval) {
        return gamevalId("item", gameval);
    }

    /** A player-var (varp) id by gameval name (e.g. {@code "WOODCUTTING_WOODBOX_LASTUSED_TIER"}). */
    public OptionalInt varpId(String gameval) {
        return gamevalId("varp", gameval);
    }

    /** A {@link Struct} (its param map) by gameval name, or empty when absent. */
    public Optional<Struct> struct(String gameval) {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT eid, data FROM node WHERE etype = 'struct' AND gameval = ? LIMIT 1")) {
            ps.setString(1, gameval);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(parseStruct(rs.getInt(1), rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            throw new AtlasException("struct(" + gameval + ")", e);
        }
        return Optional.empty();
    }

    private static Struct parseStruct(int eid, String json) {
        Map<Integer, Object> params = new LinkedHashMap<>();
        if (json != null && !json.isBlank()) {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            JsonElement pe = o.get("params");
            if (pe != null && pe.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : pe.getAsJsonObject().entrySet()) {
                    int pid;
                    try {
                        pid = Integer.parseInt(e.getKey());
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    JsonElement v = e.getValue();
                    if (v.isJsonPrimitive()) {
                        var prim = v.getAsJsonPrimitive();
                        params.put(pid, prim.isNumber() ? (Object) prim.getAsLong() : prim.getAsString());
                    }
                }
            }
        }
        return new Struct(eid, params);
    }

    // --------------------------------------------------------------- recipes

    /** The primary recipe that produces {@code itemId}, or empty when nothing makes it. */
    public Optional<Recipe> recipe(int itemId) {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT json FROM recipe WHERE product_uid = ? LIMIT 1")) {
            ps.setString(1, "item:" + itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(parseRecipe(rs.getString(1))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new AtlasException("recipe(" + itemId + ")", e);
        }
    }

    // ----------------------------------------------------------- gather spots

    /** Every gather placement that yields {@code itemId} (with coordinates). */
    public List<Spot> gatherSpots(int itemId) {
        return querySpots(
                "SELECT item,category,skill,kind,loc,loc_name,x,y,plane,level,xp "
                        + "FROM gather WHERE item = ? AND x IS NOT NULL",
                ps -> ps.setInt(1, itemId));
    }

    /** Every gather placement in a category (e.g. {@code "woodcutting"}, {@code "bank"}). */
    public List<Spot> gatherSpotsByCategory(String category) {
        return querySpots(
                "SELECT item,category,skill,kind,loc,loc_name,x,y,plane,level,xp "
                        + "FROM gather WHERE category = ? AND x IS NOT NULL",
                ps -> ps.setString(1, category));
    }

    /**
     * Every gather placement whose loc display name matches (e.g. {@code "Tree"}).
     * Needed for resources whose product item the skilling table doesn't resolve —
     * ubiquitous level-1 trees are in the Atlas as {@code loc_name='Tree'} with a
     * null item, so they can't be found by {@link #gatherSpots(int)}.
     */
    public List<Spot> gatherSpotsByLocName(String locName) {
        return querySpots(
                "SELECT item,category,skill,kind,loc,loc_name,x,y,plane,level,xp "
                        + "FROM gather WHERE loc_name = ? AND x IS NOT NULL",
                ps -> ps.setString(1, locName));
    }

    /** The nearest (Chebyshev, same plane) gather spot for {@code itemId}, or empty. */
    public Optional<Spot> nearestGatherSpot(int itemId, int x, int y, int plane) {
        Spot best = null;
        int bestD = Integer.MAX_VALUE;
        for (Spot s : gatherSpots(itemId)) {
            if (s.plane() != plane) {
                continue;
            }
            int d = s.tile().chebyshev(x, y);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return Optional.ofNullable(best);
    }

    // ------------------------------------------------------------------ banks

    /** Every bank placement (from the {@code gather} table, {@code category='bank'}). */
    public List<Tile> banks() {
        List<Tile> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT x,y,plane FROM gather WHERE category = 'bank' AND x IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Tile(rs.getInt(1), rs.getInt(2), rs.getInt(3)));
            }
        } catch (SQLException e) {
            throw new AtlasException("banks()", e);
        }
        return out;
    }

    /** The nearest bank (Chebyshev, same plane) to a position, or empty. */
    public Optional<Tile> nearestBank(int x, int y, int plane) {
        Tile best = null;
        int bestD = Integer.MAX_VALUE;
        for (Tile t : banks()) {
            if (t.plane() != plane) {
                continue;
            }
            int d = t.chebyshev(x, y);
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return Optional.ofNullable(best);
    }

    // ---------------------------------------------------------------- closure

    /**
     * Expand a production goal into its transitive dependency closure — the host
     * port of the analyzer's {@code _compute_closure}. Colored DFS over the recipe
     * graph (cycle-safe), topological build order, quantity rollup, and the raw
     * leaves (with gather spots) to acquire.
     *
     * @param targetItem item to produce
     * @param targetQty  how many
     * @param stopSkills recipe skills to treat as leaves (gather/buy, don't craft)
     */
    public BuildPlan closure(int targetItem, double targetQty, Set<String> stopSkills) {
        Map<Integer, Recipe> recipes = loadRecipeMap();
        Set<Integer> gatherable = gatherableItems();
        ClosureWalk walk = new ClosureWalk(recipes, gatherable,
                stopSkills == null ? Set.of() : stopSkills);
        walk.dfs(targetItem);

        List<Integer> topo = new ArrayList<>(walk.order);
        java.util.Collections.reverse(topo);

        // Quantity rollup: product before ingredient. Mirrors the Python exactly —
        // roll qty through any visited item that has a recipe (raws whose ingredients
        // were never visited simply roll into items not in the node set, which are
        // ignored downstream).
        Map<Integer, Double> req = new HashMap<>();
        req.put(targetItem, targetQty);
        for (int i : topo) {
            double q = req.getOrDefault(i, 0.0);
            Recipe r = recipes.get(i);
            if (r != null && q != 0.0) {
                for (Recipe.Ingredient ing : r.ingredients()) {
                    req.merge(ing.item(), q * ing.count(), Double::sum);
                }
            }
        }

        // Stamp the final required quantity onto each node (records are immutable).
        Map<Integer, BuildPlan.Node> nodes = new LinkedHashMap<>();
        for (Map.Entry<Integer, BuildPlan.Node> e : walk.nodes.entrySet()) {
            BuildPlan.Node n = e.getValue();
            nodes.put(e.getKey(), new BuildPlan.Node(n.item(), n.label(), n.hasRecipe(),
                    n.gatherable(), n.raw(), n.skill(), n.level(), n.xp(), n.makeQuantity(),
                    round2(req.getOrDefault(e.getKey(), 0.0))));
        }

        // Raw materials = raw leaves with rolled-up qty + their gather spots.
        List<BuildPlan.RawMaterial> raw = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : req.entrySet()) {
            BuildPlan.Node n = nodes.get(e.getKey());
            if (n != null && n.raw()) {
                raw.add(new BuildPlan.RawMaterial(e.getKey(), n.label(),
                        round2(e.getValue()), gatherSpots(e.getKey())));
            }
        }
        raw.sort(Comparator.comparingDouble(BuildPlan.RawMaterial::qty).reversed());

        // Total xp per skill across the craftable (non-raw) nodes.
        Map<String, Double> xpAccum = new LinkedHashMap<>();
        for (int i : topo) {
            BuildPlan.Node n = nodes.get(i);
            if (n != null && !n.raw() && n.xp() != null && n.skill() != null) {
                double q = req.getOrDefault(i, 0.0);
                if (q != 0.0) {
                    xpAccum.merge(n.skill(), q * n.xp(), Double::sum);
                }
            }
        }
        Map<String, Double> xpBySkill = new LinkedHashMap<>();
        xpAccum.forEach((k, v) -> xpBySkill.put(k, round1(v)));

        return new BuildPlan(targetItem, targetQty, List.copyOf(nodes.values()), topo, raw,
                xpBySkill, !walk.cycles.isEmpty(), walk.truncated);
    }

    // ------------------------------------------------------- closure internals

    private Map<Integer, Recipe> loadRecipeMap() {
        Map<Integer, Recipe> m = new HashMap<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT product_uid, json FROM recipe");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String puid = rs.getString(1);
                if (puid != null && puid.startsWith("item:")) {
                    int pid = Integer.parseInt(puid.substring("item:".length()));
                    m.putIfAbsent(pid, parseRecipe(rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            throw new AtlasException("loadRecipeMap", e);
        }
        return m;
    }

    private Set<Integer> gatherableItems() {
        Set<Integer> s = new HashSet<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT DISTINCT item FROM gather WHERE item IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                s.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            throw new AtlasException("gatherableItems", e);
        }
        return s;
    }

    /** Mutable per-closure DFS state (one instance per {@link #closure} call). */
    private final class ClosureWalk {
        private final Map<Integer, Recipe> recipes;
        private final Set<Integer> gatherable;
        private final Set<String> stop;
        final Map<Integer, BuildPlan.Node> nodes = new LinkedHashMap<>();
        private final Map<Integer, Integer> color = new HashMap<>(); // 0 unseen, 1 on-stack, 2 done
        final List<Integer> order = new ArrayList<>();               // postorder
        final List<List<Integer>> cycles = new ArrayList<>();
        private final Set<Set<Integer>> seenCycles = new HashSet<>();
        private final List<Integer> path = new ArrayList<>();
        boolean truncated = false;

        ClosureWalk(Map<Integer, Recipe> recipes, Set<Integer> gatherable, Set<String> stop) {
            this.recipes = recipes;
            this.gatherable = gatherable;
            this.stop = stop;
        }

        private boolean isLeaf(int i) {
            Recipe r = recipes.get(i);
            return r == null
                    || (r.skill() != null && stop.contains(r.skill()))
                    || gatherable.contains(i);
        }

        private BuildPlan.Node info(int i) {
            Recipe r = recipes.get(i);
            String skill = null;
            Integer level = null;
            Double xp = null;
            int mq = 1;
            if (r != null) {
                skill = r.skill();
                if (!r.requirements().isEmpty()) {
                    level = r.requirements().get(0).level();
                }
                if (!r.xp().isEmpty()) {
                    xp = r.xp().get(0).xp();
                }
                mq = r.makeQuantity();
            }
            return new BuildPlan.Node(i, label("item", i), r != null,
                    gatherable.contains(i), isLeaf(i), skill, level, xp, mq, 0.0);
        }

        void dfs(int i) {
            if (nodes.size() >= MAX_CLOSURE_NODES) {
                truncated = true;
                return;
            }
            color.put(i, 1);
            path.add(i);
            nodes.computeIfAbsent(i, this::info);
            Recipe r = recipes.get(i);
            if (r != null && !isLeaf(i)) {
                for (Recipe.Ingredient ing : r.ingredients()) {
                    int ci = ing.item();
                    int c = color.getOrDefault(ci, 0);
                    if (c == 1 && path.contains(ci)) {           // back edge -> cycle
                        List<Integer> cyc = new ArrayList<>(path.subList(path.indexOf(ci), path.size()));
                        cyc.add(ci);
                        if (seenCycles.add(new HashSet<>(cyc))) {
                            cycles.add(cyc);
                        }
                    } else if (c == 0) {
                        dfs(ci);
                    }
                }
            }
            path.remove(path.size() - 1);
            color.put(i, 2);
            order.add(i);
        }
    }

    // ----------------------------------------------------------- node labels

    /** Display name for an entity uid ({@code name} else {@code gameval} else fallback). */
    private String label(String etype, int eid) {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT name, gameval FROM node WHERE uid = ?")) {
            ps.setString(1, etype + ":" + eid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String name = rs.getString(1);
                    if (name != null && !name.isBlank()) {
                        return name;
                    }
                    String gv = rs.getString(2);
                    if (gv != null && !gv.isBlank()) {
                        return gv;
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("label({}:{}) failed", etype, eid, e);
        }
        return etype + " " + eid;
    }

    // ----------------------------------------------------------- row mapping

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<Spot> querySpots(String sql, Binder binder) {
        List<Spot> out = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Spot(
                            rs.getInt("item"), rs.getString("category"), rs.getString("skill"),
                            rs.getString("kind"), rs.getInt("loc"), rs.getString("loc_name"),
                            rs.getInt("x"), rs.getInt("y"), rs.getInt("plane"),
                            nullableInt(rs, "level"), nullableDouble(rs, "xp")));
                }
            }
        } catch (SQLException e) {
            throw new AtlasException("querySpots", e);
        }
        return out;
    }

    private static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        return o == null ? null : ((Number) o).intValue();
    }

    private static Double nullableDouble(ResultSet rs, String col) throws SQLException {
        Object o = rs.getObject(col);
        return o == null ? null : ((Number) o).doubleValue();
    }

    // ------------------------------------------------------- recipe.json parse

    private static Recipe parseRecipe(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        List<Recipe.Ingredient> ings = new ArrayList<>();
        for (JsonElement e : arr(o, "ingredients")) {
            JsonObject io = e.getAsJsonObject();
            ings.add(new Recipe.Ingredient(getInt(io, "item", -1), getString(io, "name"),
                    getInt(io, "count", 1)));
        }
        List<Recipe.Requirement> reqs = new ArrayList<>();
        for (JsonElement e : arr(o, "requirements")) {
            JsonObject ro = e.getAsJsonObject();
            reqs.add(new Recipe.Requirement(getInt(ro, "skill_id", -1), getString(ro, "skill"),
                    getInt(ro, "level", 1)));
        }
        List<Recipe.XpReward> xps = new ArrayList<>();
        for (JsonElement e : arr(o, "xp")) {
            JsonObject xo = e.getAsJsonObject();
            xps.add(new Recipe.XpReward(getInt(xo, "skill_id", -1), getString(xo, "skill"),
                    getDouble(xo, "xp", 0.0)));
        }
        List<Recipe.Tool> tools = new ArrayList<>();
        for (JsonElement e : arr(o, "tools")) {
            JsonObject to = e.getAsJsonObject();
            tools.add(new Recipe.Tool(getInt(to, "item", -1), getString(to, "name")));
        }
        return new Recipe(getInt(o, "product", -1), getString(o, "product_name"),
                ings, reqs, xps, tools, getInt(o, "make_quantity", 1),
                getBool(o, "members"), getString(o, "skill"));
    }

    private static int getInt(JsonObject o, String k, int def) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? def : e.getAsInt();
    }

    private static double getDouble(JsonObject o, String k, double def) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? def : e.getAsDouble();
    }

    private static String getString(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? null : e.getAsString();
    }

    private static boolean getBool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    private static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull() || !e.isJsonArray()) ? new JsonArray() : e.getAsJsonArray();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
