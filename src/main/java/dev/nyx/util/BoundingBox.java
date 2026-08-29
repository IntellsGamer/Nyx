package dev.nyx.util;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public record BoundingBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    public BoundingBox expand(double x, double y, double z) {
        return new BoundingBox(
            minX - x, minY - y, minZ - z,
            maxX + x, maxY + y, maxZ + z
        );
    }

    public BoundingBox offset(double x, double y, double z) {
        return new BoundingBox(
            minX + x, minY + y, minZ + z,
            maxX + x, maxY + y, maxZ + z
        );
    }

    public boolean intersects(BoundingBox other) {
        return this.maxX > other.minX && this.minX < other.maxX
            && this.maxY > other.minY && this.minY < other.maxY
            && this.maxZ > other.minZ && this.minZ < other.maxZ;
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX
            && y >= minY && y <= maxY
            && z >= minZ && z <= maxZ;
    }

    public double width() {
        return maxX - minX;
    }

    public double height() {
        return maxY - minY;
    }

    public double depth() {
        return maxZ - minZ;
    }

    public static BoundingBox fromPlayer(double x, double y, double z, boolean onGround) {
        double height = onGround ? 1.8 : 1.62;
        double width = 0.6;
        double halfWidth = width / 2.0;
        return new BoundingBox(
            x - halfWidth, y, z - halfWidth,
            x + halfWidth, y + height, z + halfWidth
        );
    }

    public static BoundingBox fromEntity(Entity entity) {
        var bb = entity.getBoundingBox();
        return new BoundingBox(bb.getMinX(), bb.getMinY(), bb.getMinZ(),
                               bb.getMaxX(), bb.getMaxY(), bb.getMaxZ());
    }
}
