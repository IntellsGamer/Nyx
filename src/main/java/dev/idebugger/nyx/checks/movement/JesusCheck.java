package dev.idebugger.nyx.checks.movement;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;

@CheckData(name = "Jesus", description = "Detects water walking exploits")
public class JesusCheck extends Check {

    public JesusCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void handle(NyxPlayerData data) {
        if (data.getPositionHistory().size() < 2) return;

        if (data.isGliding()) return;
        if (data.isInVehicle()) return;
        if (data.getPlayer().isFlying()) return;

        var current = data.getPositionHistory().peekFirst();
        if (current == null) return;

        Location loc = current.location();
        if (!isAboveLiquid(loc)) return;

        if (data.isInWater() || data.isInLava()) {
            double speed = data.getHorizontalSpeed();
            if (speed > 0.25) {
                flag(data, String.format("Surf S:%.3f", speed));
            }
            return;
        }

        if (data.isOnGround()) {
            double speed = data.getHorizontalSpeed();
            if (speed > 0.15) {
                flag(data, String.format("Walk S:%.3f", speed));
            }
        }
    }

    private boolean isAboveLiquid(Location loc) {
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        Block feet = world.getBlockAt(x, y, z);
        if (feet.getType().isSolid() && feet.getType() != Material.LILY_PAD) return false;

        Block below = world.getBlockAt(x, y - 1, z);
        if (below.getType().isSolid() && below.getType() != Material.LILY_PAD) return false;

        for (int dy = 1; dy <= 3; dy++) {
            if (isLiquid(world.getBlockAt(x, y - dy, z))) return true;
        }
        return false;
    }

    private boolean isLiquid(Block block) {
        Material type = block.getType();
        return type == Material.WATER
            || type == Material.LAVA
            || type == Material.BUBBLE_COLUMN
            || (block.getBlockData() instanceof Waterlogged);
    }
}
