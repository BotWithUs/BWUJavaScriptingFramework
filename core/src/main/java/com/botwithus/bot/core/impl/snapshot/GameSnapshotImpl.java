package com.botwithus.bot.core.impl.snapshot;

import com.botwithus.bot.api.snapshot.GameSnapshot;
import com.botwithus.bot.api.snapshot.Inventory;
import com.botwithus.bot.api.snapshot.InventoryItem;
import com.botwithus.bot.api.snapshot.LocalPlayer;
import com.botwithus.bot.api.snapshot.Npc;
import com.botwithus.bot.api.snapshot.NpcFilter;
import com.botwithus.bot.api.snapshot.Player;
import com.botwithus.bot.api.snapshot.PlayerFilter;
import com.botwithus.bot.api.snapshot.Skill;
import com.botwithus.bot.core.shm.LocalPlayerView;
import com.botwithus.bot.core.shm.NpcEntry;
import com.botwithus.bot.core.shm.PlayerEntry;
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

    public GameSnapshotImpl(SnapshotView view) {
        this.view = view;
    }

    @Override
    public long tickId() {
        return view.tickId();
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
    public Inventories inventories() {
        return new InventoriesImpl();
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
                e.maxHp());
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
                e.combatLevel());
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
