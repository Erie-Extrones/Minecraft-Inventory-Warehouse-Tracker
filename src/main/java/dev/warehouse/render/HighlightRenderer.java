package dev.warehouse.render;

import dev.warehouse.config.ConfigIO;
import dev.warehouse.index.ContainerEntry;
import dev.warehouse.region.RegionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Timed in-world highlights: container boxes (block or entity), beams, labels, and guide lines from the player. */
public final class HighlightRenderer {
    public static final class Highlight {
        public final String dimension;
        public final @Nullable AABB box;
        public final @Nullable UUID entityId;
        public final @Nullable BlockPos fallbackPos;
        public final int color;
        public final long expiresAtMs;
        public final @Nullable String label;
        public final boolean lineFromPlayer;
        public @Nullable Highlight linkedTo;
        public final @Nullable String group;
        /** Item this highlight is about (search / find), so the chest screen can pulse its slots. */
        public dev.warehouse.items.@Nullable ItemKey itemKey;
        public java.util.@Nullable UUID containerId;

        Highlight(String dimension, @Nullable AABB box, @Nullable UUID entityId, @Nullable BlockPos fallbackPos, int color, long expiresAtMs, @Nullable String label, boolean lineFromPlayer, @Nullable String group) {
            this.dimension = dimension;
            this.box = box;
            this.entityId = entityId;
            this.fallbackPos = fallbackPos;
            this.color = color;
            this.expiresAtMs = expiresAtMs;
            this.label = label;
            this.lineFromPlayer = lineFromPlayer;
            this.group = group;
        }

        AABB resolveBox(Minecraft mc) {
            if (entityId != null && mc.level != null) {
                Entity e = mc.level.getEntity(entityId);
                if (e != null) return e.getBoundingBox().inflate(0.1);
            }
            if (box != null) return box;
            return new AABB(fallbackPos);
        }
    }

    private final List<Highlight> active = new ArrayList<>();
    private static final int[] PALETTE = {0xFFFF4080, 0xFF40C8FF, 0xFF80FF40, 0xFFFFC040, 0xFFC080FF, 0xFF40FFD0, 0xFFFF8040, 0xFFFFFF60};
    private int paletteIndex;

    public int nextColor() {
        int c = PALETTE[paletteIndex % PALETTE.length];
        paletteIndex++;
        return c;
    }

    public List<Highlight> active() {
        return active;
    }

    public void clear() {
        active.clear();
    }

    public void clearGroup(String group) {
        active.removeIf(h -> group.equals(h.group));
    }

    public void removeContainer(String group, UUID containerId) {
        active.removeIf(h -> group.equals(h.group) && containerId.equals(h.containerId));
    }

    public Highlight container(ContainerEntry e, int color, int seconds, @Nullable String label, boolean line, @Nullable String group) {
        AABB box = null;
        if (!e.kind.isEntity()) {
            box = e.secondaryPos != null ? AABB.encapsulatingFullBlocks(e.pos, e.secondaryPos) : new AABB(e.pos);
        }
        Highlight h = new Highlight(e.dimension, box, e.kind.isEntity() ? e.entityId : null, e.pos, color, System.currentTimeMillis() + seconds * 1000L, label, line, group);
        h.containerId = e.id;
        active.add(h);
        return h;
    }

    /** Highlight a container because of a specific item; the chest screen will pulse that item's slots. */
    public Highlight containerForItem(ContainerEntry e, dev.warehouse.items.ItemKey key, int color, int seconds, @Nullable String label, boolean line, @Nullable String group) {
        Highlight h = container(e, color, seconds, label, line, group);
        h.itemKey = key;
        return h;
    }

    /** Keys of active highlights that point at this container. */
    public List<dev.warehouse.items.ItemKey> itemKeysFor(UUID containerId) {
        List<dev.warehouse.items.ItemKey> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Highlight h : active) if (h.itemKey != null && containerId.equals(h.containerId) && now <= h.expiresAtMs) out.add(h.itemKey);
        return out;
    }

    public Highlight container(ContainerEntry e, @Nullable String label) {
        return container(e, nextColor(), ConfigIO.get().highlightSeconds, label, true, null);
    }

    public Highlight spot(String dimension, Vec3 pos, int color, int seconds, @Nullable String label) {
        AABB box = AABB.ofSize(pos, 1, 1, 1);
        Highlight h = new Highlight(dimension, box, null, BlockPos.containing(pos), color, System.currentTimeMillis() + seconds * 1000L, label, true, null);
        active.add(h);
        return h;
    }

    public void pair(ContainerEntry from, ContainerEntry to, @Nullable String label) {
        int color = nextColor();
        Highlight a = container(from, color, ConfigIO.get().highlightSeconds, "from", false, null);
        Highlight b = container(to, color, ConfigIO.get().highlightSeconds, label != null ? label : "to", true, null);
        a.linkedTo = b;
    }

    public void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || active.isEmpty()) return;
        long now = System.currentTimeMillis();
        String dim = RegionManager.dimensionId(mc.level);
        Vec3 eye = mc.player.getEyePosition().add(0, -0.4, 0);
        Iterator<Highlight> it = active.iterator();
        Highlight guideTarget = null;
        try {
            while (it.hasNext()) {
                Highlight h = it.next();
                if (now > h.expiresAtMs) {
                    it.remove();
                    continue;
                }
                if (!h.dimension.equals(dim)) continue;
                AABB box = h.resolveBox(mc);
                float pulse = 0.65F + 0.35F * (float) Math.sin(now / 180.0);
                int fill = ARGB.color((int) (70 * pulse), h.color);
                Gizmos.cuboid(box.inflate(0.02), GizmoStyle.strokeAndFill(h.color, 3.0F, fill)).setAlwaysOnTop();
                Vec3 center = box.getCenter();
                // Beam so it can be spotted from afar.
                Gizmos.line(new Vec3(center.x, box.maxY, center.z), new Vec3(center.x, box.maxY + 6, center.z), ARGB.color(160, h.color), 2.0F).setAlwaysOnTop();
                if (h.label != null) {
                    Gizmos.billboardText(h.label, new Vec3(center.x, box.maxY + 0.6, center.z), TextGizmo.Style.forColorAndCentered(h.color).withScale(0.35F)).setAlwaysOnTop();
                }
                if (h.lineFromPlayer) guideTarget = h; // the most recently added guided highlight wins
                if (h.linkedTo != null) {
                    AABB other = h.linkedTo.resolveBox(mc);
                    Gizmos.arrow(center, other.getCenter(), h.color, 3.0F).setAlwaysOnTop();
                }
            }
        } catch (IllegalStateException ignored) {
        }
        if (guideTarget != null) dev.warehouse.WarehouseClient.guidePath().request(guideTarget.resolveBox(mc), guideTarget.color, 5);
    }
}
