package dev.warehouse.claims;

import java.util.UUID;

/** One line of GriefPrevention's /claimlist: lesser corner + area. Bounds are captured later via visualization. */
public final class ClaimAnchor {
    public String world;
    public int lesserX;
    public int lesserZ;
    public int area;
    public UUID boundRegionId;
    public long importedEpochMs;

    public ClaimAnchor() {}

    public ClaimAnchor(String world, int lesserX, int lesserZ, int area) {
        this.world = world;
        this.lesserX = lesserX;
        this.lesserZ = lesserZ;
        this.area = area;
        this.importedEpochMs = System.currentTimeMillis();
    }

    public boolean sameAnchor(ClaimAnchor o) {
        return world.equalsIgnoreCase(o.world) && lesserX == o.lesserX && lesserZ == o.lesserZ;
    }

    public String displayName() {
        return "Claim " + world + " " + lesserX + "," + lesserZ;
    }
}
