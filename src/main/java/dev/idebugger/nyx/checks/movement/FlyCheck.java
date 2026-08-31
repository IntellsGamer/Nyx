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

    // In a tight vertical space (low ceiling, ~1-2 blocks of headroom) rapidly
    // pressing jump makes the player bounce off the ceiling and back down every
    // few ticks. The ceiling cuts each jump arc early, so the observed deltaY
    // never follows the vanilla air-drag curve and the check flags a false
    // "fly". Skip the whole check whenever there's solid ground above so those
    // convoluted ceiling-bounce motions are never treated as flight.
    private static final int MAX_HEADROOM_BLOCKS = 3;

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

        // Knockback re-applied by the velocity check temporarily launches the
        // player upward. Skip detection until the grace window expires to avoid
        // false-positive fly VL inflation right after a legit hit.
        long kbGrace = plugin.getNyxConfig().getKnockbackGracePeriodMs();
        if (kbGrace > 0 && System.currentTimeMillis() - data.getLastKnockbackAppliedTime() < kbGrace) return;

        // Swimming is server-authoritative while the player is on the surface;
        // keep skipping until the swim state fully winds down as well.
        if (data.isSwimming()) return;

        if (data.isRecentlyInLiquid(LIQUID_RECENCY_MS)) return;

        Location loc = data.getPositionHistory().peekFirst().location();
        if (isNearOrAboveLiquid(loc)) return;

        // Powdered snow / cobweb either below (sinking out of it) or within the
        // 2-block reach: the client's vertical velocity is not trustworthy here.
        if (isNearWebOrPowderedSnow(loc)) return;

        // Low ceiling overhead: bouncing up into solid blocks makes vertical
        // motion erratic and untrustworthy. Skip here so rapid jumps in tight
        // spaces don't false flag as flight.
        if (isLowHeadroom(loc)) return;

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

    /**
     * True when solid ground sits within MAX_HEADROOM_BLOCKS above the player.
     * In that tight space every jump arc is cut short by the ceiling, so the
     * vertical velocity is dominated by ceiling collisions rather than vanilla
     * gravity/air-drag and must not be judged as flight.
     */
    private boolean isLowHeadroom(Location loc) {
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int eyeY = (int) Math.floor(loc.getY() + 1.62);
        int topY = eyeY + MAX_HEADROOM_BLOCKS;

        for (int y = eyeY + 1; y <= topY; y++) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType().isCollidable()) {
                return true;
            }
        }
        return false;
    }
}

