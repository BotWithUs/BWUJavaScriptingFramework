package com.botwithus.bot.core.impl.snapshot;

import com.botwithus.bot.api.snapshot.DynamicRegion;
import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.GroundItem;
import com.botwithus.bot.api.snapshot.GroundItemFilter;
import com.botwithus.bot.api.snapshot.Inventory;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Location;
import com.botwithus.bot.api.snapshot.LocationFilter;
import com.botwithus.bot.api.snapshot.Npc;
import com.botwithus.bot.api.snapshot.NpcFilter;
import com.botwithus.bot.api.snapshot.Player;
import com.botwithus.bot.api.snapshot.PlayerFilter;
import com.botwithus.bot.api.snapshot.Projectile;
import com.botwithus.bot.api.snapshot.ProjectileFilter;
import com.botwithus.bot.api.snapshot.Skill;
import com.botwithus.bot.core.shm.GroundItemEntry;
import com.botwithus.bot.core.shm.LocalPlayerView;
import com.botwithus.bot.core.shm.LocationEntry;
import com.botwithus.bot.core.shm.NpcEntry;
import com.botwithus.bot.core.shm.PlayerEntry;
import com.botwithus.bot.core.shm.ProjectileEntry;
import com.botwithus.bot.core.shm.SkillEntry;
import com.botwithus.bot.core.shm.SnapshotView;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * {@link GameSnapshot} implementation backed by a {@link SnapshotView} sliced
 * out of the producer's shared-memory mapping. One instance per acquisition;
 * the writer may overwrite the underlying buffer as soon as the next snapshot
 * is published, so don't cache instances or any records they return across
 * the tick boundary.
 *
 * <p>Records are constructed on read. At full caps (1024 NPCs, 2048 players,
 * 2048 inventory items) that's a few KB of garbage per tick if every
 * accessor is exercised — acceptable today, swap to flyweight views if it
 * shows up in profiles.</p>
 */
public final class GameSnapshotImpl implements GameSnapshot {

    private final SnapshotView view;

    /**
     * Memoised {@link #dynamicRegion()} result. Acquiring the region allocates
     * three objects (header record, memory slice, flyweight), and the natural way
     * to write a per-tile resolve loop is
     * {@code snapshot.dynamicRegion().sourceOfPacked(...)} — which would put
     * those three allocations on the per-tile path and defeat the whole point of
     * the allocation-free resolver. Caching makes the naive loop cost the same as
     * the careful one.
     *
     * <p>Deliberately unsynchronised. This class is documented as one instance
     * per acquisition, and a racing double-initialisation is benign: every field
     * of the view is {@code final}, so the JMM guarantees a racing reader sees a
     * fully-built object, and two views over the same buffer are
     * interchangeable.</p>
     */
    private DynamicRegion dynamicRegion;

    public GameSnapshotImpl(SnapshotView view) {
        this.view = view;
    }

    @Override
    public int serverTick() {
        return view.serverTick();
    }

    @Override
    public int gameCycle() {
        return view.gameCycle();
    }

    @Override
    public long publishSeq() {
        return view.publishSeq();
    }

    @Override
    public int gameState() {
        return view.gameState();
    }

    @Override
    public int ownIndex() {
        return view.ownIndex();
    }

    @Override
    public LocalPlayer self() {
        if (view.ownIndex() < 0) {
            return null;
        }
        LocalPlayerView lpv = view.self();
        if (lpv.serverIndex() < 0) {
            return null;
        }
        int skillCount = lpv.skillCount();
        List<Skill> skills = new ArrayList<>(skillCount);
        for (int i = 0; i < skillCount; i++) {
            SkillEntry s = lpv.skill(i);
            skills.add(new Skill(s.typeId(), s.experience(), s.actualLevel(), s.boostedLevel()));
        }
        return new LocalPlayer(
                lpv.serverIndex(),
                lpv.combatLevel(),
                lpv.tileX(),
                lpv.tileY(),
                lpv.plane(),
                lpv.flags(),
                lpv.followingIndex(),
                lpv.animationId(),
                lpv.stanceId(),
                lpv.targetIndex(),
                lpv.targetType(),
                lpv.isMember(),
                lpv.spotAnimId(),
                // Health lives in varps, not in the mapping. This path is the
                // RPC-free one by contract, so it leaves both at the sentinel;
                // GameAPIImpl.getLocalPlayer fills them from get_varps.
                LocalPlayer.HEALTH_UNKNOWN,
                LocalPlayer.HEALTH_UNKNOWN,
                skills);
    }

    @Override
    public Npcs npcs() {
        return new NpcsImpl();
    }

    @Override
    public Players players() {
        return new PlayersImpl();
    }

    @Override
    public Locations locations() {
        return new LocationsImpl();
    }

    @Override
    public Inventories inventories() {
        return new InventoriesImpl();
    }

    @Override
    public GroundItems groundItems() {
        return new GroundItemsImpl();
    }

    @Override
    public Projectiles projectiles() {
        return new ProjectilesImpl();
    }

    @Override
    public int sceneVersion() {
        return view.sceneVersion();
    }

    @Override
    public DynamicRegion dynamicRegion() {
        DynamicRegion cached = dynamicRegion;
        if (cached == null) {
            cached = view.dynamicRegion();
            dynamicRegion = cached;
        }
        return cached;
    }

    @Override
    public boolean isInterfaceOpen(int ifaceId) {
        return view.isInterfaceOpen(ifaceId);
    }

    private static Npc toNpc(NpcEntry e) {
        return new Npc(
                e.serverIndex(),
                e.typeId(),
                e.tileX(),
                e.tileY(),
                e.plane(),
                e.flags(),
                e.followingIndex(),
                e.animationId(),
                e.stanceId(),
                e.hp(),
                e.maxHp(),
                e.spotAnimId());
    }

    private static Player toPlayer(PlayerEntry e) {
        return new Player(
                e.serverIndex(),
                e.tileX(),
                e.tileY(),
                e.plane(),
                e.flags(),
                e.followingIndex(),
                e.animationId(),
                e.stanceId(),
                e.combatLevel(),
                e.spotAnimId());
    }

    private static GroundItem toGroundItem(GroundItemEntry e) {
        return new GroundItem(
                e.itemId(),
                e.quantity(),
                e.tileX(),
                e.tileY(),
                e.plane());
    }

    private static Projectile toProjectile(ProjectileEntry e) {
        return new Projectile(
                e.projectileId(),
                e.startCycle(),
                e.endCycle(),
                e.sourceIndex(),
                e.sourceType(),
                e.targetIndex(),
                e.targetType(),
                e.startTileX(),
                e.startTileY(),
                e.endTileX(),
                e.endTileY(),
                e.plane());
    }

    private static Location toLocation(LocationEntry e) {
        return new Location(
                e.typeId(),
                e.interactId(),
                e.animationId(),
                e.tileX(),
                e.tileY(),
                e.plane(),
                e.shape(),
                e.rotation(),
                e.flags());
    }

    private final class NpcsImpl implements Npcs {

        @Override
        public int count() {
            return view.npcCount();
        }

        @Override
        public Npc at(int index) {
            return toNpc(view.npcAt(index));
        }

        @Override
        public Optional<Npc> byServerIndex(int serverIndex) {
            int n = view.npcCount();
            for (int i = 0; i < n; i++) {
                NpcEntry e = view.npcAt(i);
                if (e.serverIndex() == serverIndex) {
                    return Optional.of(toNpc(e));
                }
            }
            return Optional.empty();
        }

        @Override
        public List<Npc> filter(NpcFilter filter) {
            int n = view.npcCount();
            List<Npc> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Npc rec = toNpc(view.npcAt(i));
                if (filter.test(rec)) {
                    out.add(rec);
                }
            }
            return out;
        }

        @Override
        public Stream<Npc> stream() {
            return IntStream.range(0, view.npcCount()).mapToObj(this::at);
        }
    }

    private final class PlayersImpl implements Players {

        @Override
        public int count() {
            return view.playerCount();
        }

        @Override
        public Player at(int index) {
            return toPlayer(view.playerAt(index));
        }

        @Override
        public Optional<Player> byServerIndex(int serverIndex) {
            int n = view.playerCount();
            for (int i = 0; i < n; i++) {
                PlayerEntry e = view.playerAt(i);
                if (e.serverIndex() == serverIndex) {
                    return Optional.of(toPlayer(e));
                }
            }
            return Optional.empty();
        }

        @Override
        public List<Player> filter(PlayerFilter filter) {
            int n = view.playerCount();
            List<Player> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Player rec = toPlayer(view.playerAt(i));
                if (filter.test(rec)) {
                    out.add(rec);
                }
            }
            return out;
        }

        @Override
        public Stream<Player> stream() {
            return IntStream.range(0, view.playerCount()).mapToObj(this::at);
        }
    }

    private final class LocationsImpl implements Locations {

        @Override
        public int count() {
            return view.locationCount();
        }

        @Override
        public Location at(int index) {
            return toLocation(view.locationAt(index));
        }

        @Override
        public List<Location> filter(LocationFilter filter) {
            int n = view.locationCount();
            List<Location> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Location rec = toLocation(view.locationAt(i));
                if (filter.test(rec)) {
                    out.add(rec);
                }
            }
            return out;
        }

        @Override
        public Stream<Location> stream() {
            return IntStream.range(0, view.locationCount()).mapToObj(this::at);
        }
    }

    private final class GroundItemsImpl implements GroundItems {

        @Override
        public int count() {
            return view.groundItemCount();
        }

        @Override
        public GroundItem at(int index) {
            return toGroundItem(view.groundItemAt(index));
        }

        @Override
        public List<GroundItem> filter(GroundItemFilter filter) {
            int n = view.groundItemCount();
            List<GroundItem> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                GroundItem rec = toGroundItem(view.groundItemAt(i));
                if (filter.test(rec)) {
                    out.add(rec);
                }
            }
            return out;
        }

        @Override
        public Stream<GroundItem> stream() {
            return IntStream.range(0, view.groundItemCount()).mapToObj(this::at);
        }
    }

    private final class ProjectilesImpl implements Projectiles {

        @Override
        public int count() {
            return view.projectileCount();
        }

        @Override
        public Projectile at(int index) {
            return toProjectile(view.projectileAt(index));
        }

        @Override
        public List<Projectile> filter(ProjectileFilter filter) {
            int n = view.projectileCount();
            List<Projectile> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Projectile rec = toProjectile(view.projectileAt(i));
                if (filter.test(rec)) {
                    out.add(rec);
                }
            }
            return out;
        }

        @Override
        public Stream<Projectile> stream() {
            return IntStream.range(0, view.projectileCount()).mapToObj(this::at);
        }
    }

    private final class InventoriesImpl implements Inventories {

        @Override
        public int count() {
            return view.inventoryCount();
        }

        @Override
        public Inventory at(int index) {
            int invId = view.invId(index);
            int slotCount = view.slotCount(index);
            int firstFlatIdx = view.firstItemIdx(index);
            int flatTotal = view.invItemCount();
            List<InventoryItem> items = new ArrayList<>(slotCount);
            for (int slot = 0; slot < slotCount; slot++) {
                int flatIdx = firstFlatIdx + slot;
                int itemId = -1;
                int qty = 0;
                if (flatIdx >= 0 && flatIdx < flatTotal) {
                    itemId = view.itemIdAt(flatIdx);
                    qty = view.itemQtyAt(flatIdx);
                }
                items.add(new InventoryItem(slot, itemId, qty));
            }
            return new Inventory(invId, slotCount, items);
        }

        @Override
        public Optional<Inventory> byInvId(int invId) {
            int n = view.inventoryCount();
            for (int i = 0; i < n; i++) {
                if (view.invId(i) == invId) {
                    return Optional.of(at(i));
                }
            }
            return Optional.empty();
        }

        @Override
        public Stream<Inventory> stream() {
            return IntStream.range(0, view.inventoryCount()).mapToObj(this::at);
        }
    }
}
