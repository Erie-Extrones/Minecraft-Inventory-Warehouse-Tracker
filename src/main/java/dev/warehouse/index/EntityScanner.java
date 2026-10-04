package dev.warehouse.index;

import dev.warehouse.config.ConfigIO;
import dev.warehouse.region.Region;
import dev.warehouse.region.RegionManager;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Indexes armor stands and item frames inside regions as containers. */
public final class EntityScanner {
    private static final EquipmentSlot[] STAND_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

    private final ContainerIndex index;
    private final RegionManager regions;
    private final Snapshotter snapshotter;
    private final Map<UUID, Integer> missingScans = new HashMap<>();
    private int ticks;
    private int rescanIn = -1;

    public EntityScanner(ContainerIndex index, RegionManager regions, Snapshotter snapshotter) {
        this.index = index;
        this.regions = regions;
        this.snapshotter = snapshotter;
    }

    public void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() && (entity instanceof ArmorStand || entity instanceof ItemFrame)) rescanIn = 10;
            return InteractionResult.PASS;
        });
    }

    public void tick(Minecraft mc) {
        if (mc.level == null) return;
        ticks++;
        if (rescanIn >= 0 && rescanIn-- == 0) {
            scan(mc, false);
            return;
        }
        if (ticks % Math.max(20, ConfigIO.get().entityScanIntervalTicks) == 0) scan(mc, true);
    }

    public void scan(Minecraft mc, boolean trackMissing) {
        if (mc.level == null) return;
        String dim = RegionManager.dimensionId(mc.level);
        Set<UUID> seen = new HashSet<>();
        boolean changed = false;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand) && !(entity instanceof ItemFrame)) continue;
            Region region = regions.regionAt(dim, entity.blockPosition());
            if (region == null) continue;
            seen.add(entity.getUUID());
            List<ItemStack> stacks = new ArrayList<>();
            ContainerKind kind;
            if (entity instanceof ArmorStand stand) {
                kind = ContainerKind.ARMOR_STAND;
                for (EquipmentSlot slot : STAND_SLOTS) stacks.add(stand.getItemBySlot(slot));
            } else {
                kind = ContainerKind.ITEM_FRAME;
                stacks.add(((ItemFrame) entity).getItem());
            }
            ContainerEntry e = index.byEntity(entity.getUUID());
            boolean any = stacks.stream().anyMatch(s -> !s.isEmpty());
            if (e == null) {
                if (!any) continue;
                e = new ContainerEntry(UUID.randomUUID(), kind, dim, entity.blockPosition().immutable());
                e.entityId = entity.getUUID();
            }
            List<StackRecord> fresh = snapshotter.snapshot(stacks);
            if (!sameContents(e.contents, fresh) || !e.pos.equals(entity.blockPosition())) {
                e.contents = fresh;
                e.pos = entity.blockPosition().immutable();
                e.regionId = region.id;
                e.slotCount = stacks.size();
                e.lastSeenEpochMs = System.currentTimeMillis();
                final java.util.UUID eid = e.id;
                index.all().removeIf(x -> x.id.equals(eid));
                index.all().add(e);
                changed = true;
            } else {
                e.lastSeenEpochMs = System.currentTimeMillis();
            }
        }
        if (trackMissing) {
            int limit = ConfigIO.get().entityMissingScansBeforeRemove;
            List<UUID> toRemove = new ArrayList<>();
            for (ContainerEntry e : index.all()) {
                if (!e.kind.isEntity() || !dim.equals(e.dimension) || e.entityId == null) continue;
                if (seen.contains(e.entityId)) {
                    missingScans.remove(e.entityId);
                    continue;
                }
                if (!mc.level.isLoaded(e.pos)) continue;
                int n = missingScans.merge(e.entityId, 1, Integer::sum);
                if (n >= limit) toRemove.add(e.id);
            }
            for (UUID id : toRemove) {
                index.all().removeIf(x -> x.id.equals(id));
                changed = true;
            }
        }
        if (changed) index.fireChanged();
    }

    private static boolean sameContents(List<StackRecord> a, List<StackRecord> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            StackRecord x = a.get(i), y = b.get(i);
            if (x.slot != y.slot || x.count != y.count || !x.key.equals(y.key)) return false;
        }
        return true;
    }
}
