package dev.warehouse.region;

import net.minecraft.core.BlockPos;

import java.util.UUID;

/** Axis-aligned box in one dimension. Claims are full world height. Mutable for renames. */
public final class Region {
    public UUID id;
    public String name;
    public RegionType type;
    public String dimension;
    public int minX, minY, minZ, maxX, maxY, maxZ;

    public Region() {}

    public Region(UUID id, String name, RegionType type, String dimension, BlockPos a, BlockPos b) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.dimension = dimension;
        this.minX = Math.min(a.getX(), b.getX());
        this.minY = Math.min(a.getY(), b.getY());
        this.minZ = Math.min(a.getZ(), b.getZ());
        this.maxX = Math.max(a.getX(), b.getX());
        this.maxY = Math.max(a.getY(), b.getY());
        this.maxZ = Math.max(a.getZ(), b.getZ());
    }

    public boolean contains(String dim, int x, int y, int z) {
        return dim.equals(dimension) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(String dim, BlockPos pos) {
        return contains(dim, pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockPos min() {
        return new BlockPos(minX, minY, minZ);
    }

    public BlockPos max() {
        return new BlockPos(maxX, maxY, maxZ);
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public String describe() {
        return name + " [" + type.name().toLowerCase() + "] " + dimension + " (" + minX + "," + minY + "," + minZ + ") -> (" + maxX + "," + maxY + "," + maxZ + ")";
    }
}
