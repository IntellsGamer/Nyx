package dev.nyx.checks.movement;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;
import dev.nyx.util.BoundingBox;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;


@CheckData(name = "Phase", description = "Detects noclip and wall glitching")
public class PhaseCheck extends Check {

    public PhaseCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void handle(NyxPlayerData data) {
        if (data.getPositionHistory().size() < 2) return;

        if (data.isInVehicle()) return;
        if (data.getPlayer().isInsideVehicle()) return;

        var current = data.getPositionHistory().peekFirst();
        if (current == null) return;

        Location to = current.location();
        World world = to.getWorld();

        if (world == null) return;

        double dx = data.getDeltaX();
        double dy = data.getDeltaY();
        double dz = data.getDeltaZ();

        double speed = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (speed > 5.0) {
            BoundingBox playerBox = BoundingBox.fromPlayer(to.getX(), to.getY(), to.getZ(), data.isOnGround());

            BoundingBox expandedBox = playerBox.expand(0.5, 0.5, 0.5);

            int minX = (int) Math.floor(expandedBox.minX());
            int minY = (int) Math.floor(expandedBox.minY());
            int minZ = (int) Math.floor(expandedBox.minZ());
            int maxX = (int) Math.floor(expandedBox.maxX());
            int maxY = (int) Math.floor(expandedBox.maxY());
            int maxZ = (int) Math.floor(expandedBox.maxZ());

            int collidableBlocks = 0;
            StringBuilder blockTypes = new StringBuilder();
            int maxBlocks = 5;

            for (int x = minX; x <= maxX && collidableBlocks < maxBlocks; x++) {
                for (int y = minY; y <= maxY && collidableBlocks < maxBlocks; y++) {
                    for (int z = minZ; z <= maxZ && collidableBlocks < maxBlocks; z++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getType().isCollidable()) {
                            BoundingBox blockBox = new BoundingBox(
                                x, y, z, x + 1, y + 1, z + 1
                            );
                            if (playerBox.intersects(blockBox)) {
                                collidableBlocks++;
                                if (blockTypes.length() > 0) blockTypes.append(",");
                                blockTypes.append(block.getType().name());
                            }
                        }
                    }
                }
            }

            if (collidableBlocks > 0) {
                flag(data, String.format(
                    "BLOCKS:%d SPEED:%.2f TYPES:%s",
                    collidableBlocks, speed, blockTypes
                ));
            }
        }
    }
}
