package dev.warehouse.index;

import dev.warehouse.items.ItemKey;
import dev.warehouse.storage.JsonStore;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** All known containers for the current server. */
public final class ContainerIndex {
    private final JsonStore<List<ContainerEntry>> store;
    private final List<Runnable> changeListeners = new ArrayList<>();

    public ContainerIndex(JsonStore<List<ContainerEntry>> store) {
        this.store = store;
    }

    public List<ContainerEntry> all() {
        return store.get();
    }

    public void onChange(Runnable r) {
        changeListeners.add(r);
    }

    public void fireChanged() {
        store.markDirty();
        for (Runnable r : changeListeners) r.run();
    }

    public @Nullable ContainerEntry byId(@Nullable UUID id) {
        if (id == null) return null;
        for (ContainerEntry e : store.get()) if (e.id.equals(id)) return e;
        return null;
    }

    public @Nullable ContainerEntry atBlock(String dimension, BlockPos pos) {
        for (ContainerEntry e : store.get()) if (!e.kind.isEntity() && e.coversPos(dimension, pos)) return e;
        return null;
    }

    public @Nullable ContainerEntry byEntity(UUID entityId) {
        for (ContainerEntry e : store.get()) if (entityId.equals(e.entityId)) return e;
        return null;
    }

    public @Nullable ContainerEntry enderChest() {
        for (ContainerEntry e : store.get()) if (e.kind == ContainerKind.ENDER_CHEST) return e;
        return null;
    }

    public void upsert(ContainerEntry e) {
        store.get().removeIf(x -> x.id.equals(e.id));
        store.get().add(e);
        fireChanged();
    }

    public boolean remove(UUID id) {
        boolean r = store.get().removeIf(x -> x.id.equals(id));
        if (r) fireChanged();
        return r;
    }

    public void clear() {
        store.get().clear();
        fireChanged();
    }

    /** Containers holding the key, most count first then most recent. */
    public List<ContainerEntry> holding(ItemKey key) {
        List<ContainerEntry> out = new ArrayList<>();
        for (ContainerEntry e : store.get()) if (e.totalCount(key) > 0) out.add(e);
        out.sort((a, b) -> {
            int c = Integer.compare(b.totalCount(key), a.totalCount(key));
            return c != 0 ? c : Long.compare(b.lastSeenEpochMs, a.lastSeenEpochMs);
        });
        return out;
    }

    public int totalCount(ItemKey key) {
        int n = 0;
        for (ContainerEntry e : store.get()) n += e.totalCount(key);
        return n;
    }

    public List<ContainerEntry> inRegion(UUID regionId) {
        List<ContainerEntry> out = new ArrayList<>();
        for (ContainerEntry e : store.get()) if (regionId.equals(e.regionId)) out.add(e);
        return out;
    }
}
