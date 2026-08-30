package dev.idebugger.nyx.checks.movement;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.potion.PotionEffectType;

@CheckData(name = "Fly", description = "Detects flight, air jump, and hover exploits")
public class FlyCheck extends Check {

    private static final double AIR_DRAG = 0.98;
    private static final double GRAVITY = 0.08;
    private static final double TOLERANCE = 0.05;
    private static final int MAX_AIR_ASCENDING_TICKS = 10;

    // Water surface behaviour: jumping/swimming at a water surface legitimately
    // pops the player's torso (and the server-side "in water" flag) out of the
    // liquid for most of each hop. Gracefully skip the check while the player
    // is still interacting with the surface or hovering right above it.
    private static final long LIQUID_RECENCY_MS = 1500;
    private static final double LIQUID_BELOW_DISTANCE = 1.5;

    public FlyCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void runAsync(NyxPlayerData data) {
        if (!canRun(data)) return;
        handle(data);
    }

    @Override
    public void handle(NyxPlayerData data) {
        if (data.getPositionHistory().size() < 3) return;

        if (data.isGliding()) return;
        if (data.isInVehicle()) return;
        if (data.getPlayer().isFlying()) return;
        if (data.isInWater() || data.isInLava()) return;
        if (data.isInWeb()) return;
        if (data.isClimbing()) return;
        if (data.getPlayer().hasPotionEffect(PotionEffectType.SLOW_FALLING)) return;

        // Swimming is server-authoritative while the player is on the surface;
        // keep skipping until the swim state fully winds down as well.
        if (data.isSwimming()) return;

        if (data.isRecentlyInLiquid(LIQUID_RECENCY_MS)) return;

        Location loc = data.getPositionHistory().peekFirst().location();
        if (isNearOrAboveLiquid(loc)) return;

        if (data.isOnGround()) return;

        double deltaY = data.getDeltaY();
        double lastDeltaY = data.getLastDeltaY();
        int airTicks = data.getServerAirTicks();

        if (Math.abs(deltaY) < 0.001) return;

        if (airTicks == 1) {
            if (deltaY > 0.5) {
                flag(data, String.format("Ascend DY:%.4f T:%d", deltaY, airTicks));
            }
            return;
        }

        if (deltaY > 0.001) {
            if (airTicks > MAX_AIR_ASCENDING_TICKS) {
                flag(data, String.format("Ascend DY:%.4f T:%d", deltaY, airTicks));
            }
            return;
        }

        double expected = lastDeltaY * AIR_DRAG - GRAVITY;
        double diff = Math.abs(deltaY - expected);

        if (diff > TOLERANCE) {
            flag(data, String.format("DY:%.4f EX:%.4f D:%.4f", deltaY, expected, diff));
        }
    }

    /**
     * True when the player's feet are right at/above a liquid surface, e.g. the
     * apex of a jump made from water, or standing on a one-deep puddle.
     */
    private boolean isNearOrAboveLiquid(Location loc) {
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int y = (int) Math.floor(loc.getY());

        int maxDy = (int) Math.ceil(LIQUID_BELOW_DISTANCE);
        for (int dy = 0; dy <= maxDy; dy++) {
            Block block = world.getBlockAt(x, y - dy, z);
            if (isLiquid(block)) return true;
        }
        return false;
    }

    private boolean isLiquid(Block block) {
        Material type = block.getType();
        return type == Material.WATER || type == Material.LAVA;
    }
}

