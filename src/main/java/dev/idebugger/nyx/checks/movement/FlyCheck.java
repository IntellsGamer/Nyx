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

    // Powdered snow and cobwebs slow the player's motion (and let the client sink
    // slowly through them for powdered snow), which looks identical to the hover
    // / slow-fall signature this check hunts for. Skip while inside either block,
    // and also while right next to them (within NEAR_BLOCKS) so the abrupt
    // momentum changes while walking into/out of them never flag.
    private static final int NEAR_BLOCKS = 2;

    // Riptide launches the player upward through the air/water; the boost is
    // legit but looks exactly like an ascend-hack, so skip the launch window.
    private static final long RIPTIDE_GRACE_MS = 4000;

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
        if (System.currentTimeMillis() - data.getLastRiptideTime() < RIPTIDE_GRACE_MS) return;
        if (data.isInWater() || data.isInLava()) return;
        if (data.isInWeb() || data.isInPowderedSnow()) return;
        if (data.isClimbing()) return;
        if (data.getPlayer().hasPotionEffect(PotionEffectType.SLOW_FALLING)) return;

        // Swimming is server-authoritative while the player is on the surface;
        // keep skipping until the swim state fully winds down as well.
        if (data.isSwimming()) return;

        if (data.isRecentlyInLiquid(LIQUID_RECENCY_MS)) return;

        Location loc = data.getPositionHistory().peekFirst().location();
        if (isNearOrAboveLiquid(loc)) return;

        // Powdered snow / cobweb either below (sinking out of it) or within the
        // 2-block reach: the client's vertical velocity is not trustworthy here.
        if (isNearWebOrPowderedSnow(loc)) return;

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

    /**
     * True when a powdered-snow or cobweb block is inside (or within a small
     * radius of) the player's bounding box. Movement through these blocks applies
     * unusual vertical drag that this check must not treat as flight.
     */
    private boolean isNearWebOrPowderedSnow(Location loc) {
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int y = (int) Math.floor(loc.getY());
        int z = loc.getBlockZ();

        for (int bx = x - NEAR_BLOCKS; bx <= x + NEAR_BLOCKS; bx++) {
            for (int by = y - NEAR_BLOCKS; by <= y + NEAR_BLOCKS; by++) {
                for (int bz = z - NEAR_BLOCKS; bz <= z + NEAR_BLOCKS; bz++) {
                    Material type = world.getBlockAt(bx, by, bz).getType();
                    if (type == Material.COBWEB || type == Material.POWDER_SNOW) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}

