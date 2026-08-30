package dev.idebugger.nyx.checks.movement;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;
import dev.idebugger.nyx.util.BoundingBox;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects "boat fly": making a boat climb or sprint through the air without
 * any solid ground or liquid underneath. Boating is naturally bouncy — the boat
 * rides waves, bobbles at the surface and can briefly lift a block — so this
 * check is deliberately lenient:
 *  - any solid/liquid within ~4 blocks below counts as "surface" (skimming);
 *  - on-surface speed caps are generous and buffered;
 *  - airborne horizontal speed must be extreme AND sustained;
 *  - a sustained airborne climb is the real tell, gated behind a large buffer
 *    and fully suppressed for a few seconds after leaving ice/blue ice.
 */
@CheckData(name = "BoatFly", description = "Detects flying or speed-hacking with boats")
public class BoatFlyCheck extends Check {

    private static final long ENTER_GRACE_MS = 3000;
    private static final long ICE_MOMENTUM_MS = 2500;

    private static final double SKIM_BOX_DOWN = 4.0;
    private static final double SURFACE_BOX_XZ = 0.9;

    // —— Airborne signals ——
    private static final double CLIMB_MIN_DY = 0.05;
    private static final double CLIMB_TRIGGER = 0.9;
    private static final int CLIMB_MIN_AIR_TICKS = 8;
    private static final double EXTREME_AIR_SPEED = 2.6;
    private static final double ICE_AIR_SPEED = 6.0;
    private static final double AIR_SPEED_BUFFER = 20.0;
    private static final double CLIMB_BUFFER_DECAY = 0.10;

    // —— On-surface caps (blocks/tick) ——
    private static final double SPEED_LAND = 1.0;
    private static final double SPEED_WATER = 1.8;
    private static final double SPEED_GROUND = 1.4;
    private static final double SPEED_ICE = 4.0;
    private static final double SPEED_BLUE_ICE = 6.5;
    private static final double SURFACE_SPEED_BUFFER = 3.0;
    private static final double SURFACE_SPEED_DECAY = 1.0;

    private final Map<UUID, BoatState> stateMap = new ConcurrentHashMap<>();

    public BoatFlyCheck(Nyx plugin) {
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
        Player player = data.getPlayer();
        if (!player.isInsideVehicle()) return;

        Entity vehicle = player.getVehicle();
        if (!(vehicle instanceof Boat)) return;

        if (data.getPositionHistory().size() < 2) return;

        if (System.currentTimeMillis() - data.getLastVehicleEnterTime() < ENTER_GRACE_MS) return;

        Location boatLoc = vehicle.getLocation();
        Block at = boatLoc.getBlock();
        Block below = at.getRelative(BlockFace.DOWN);

        BoatState state = stateMap.computeIfAbsent(player.getUniqueId(), k -> new BoatState());

        if (isIce(at.getType()) || isIce(below.getType())) {
            state.lastIceMomentum = System.currentTimeMillis();
        }
        boolean iceMomentum = System.currentTimeMillis() - state.lastIceMomentum < ICE_MOMENTUM_MS;

        if (hasSurfaceNearby(boatLoc)) {
            // Boat is on/above a solid surface or a liquid (water, lava): a
            // legit boat skims these. Speed caps are generous and buffered so a
            // single wave/paddle spike never trips the check.
            state.airTicks = 0;
            state.climbBuffer = 0;
            state.speedBuffer = Math.max(0, state.speedBuffer - SURFACE_SPEED_DECAY);

            double max = surfaceMaxSpeed(at, below);
            double speed = data.getHorizontalSpeed();
            if (speed < 0.01) return;

            if (speed > max) {
                state.speedBuffer += 1.0;
                if (state.speedBuffer >= SURFACE_SPEED_BUFFER) {
                    state.speedBuffer = 0;
                    flag(data, String.format("S:%.3f M:%.3f %s", speed, max, surfaceLabel(at, below)));
                }
            }
            return;
        }

        // True airborne boat: no solid/liquid anywhere in the skim box.
        state.airTicks++;
        double speed = data.getHorizontalSpeed();
        double dy = data.getDeltaY();

        if (iceMomentum) {
            // Leaving an ice/blue-ice highway launches a boat through the air at
            // extreme speed and altitude for a couple of seconds. Only absurd
            // (unreachable in vanilla) speeds react during this window.
            if (speed > ICE_AIR_SPEED) {
                state.speedBuffer += 1.0;
                if (state.speedBuffer >= AIR_SPEED_BUFFER) {
                    state.speedBuffer = 0;
                    flag(data, String.format("AIR S:%.3f", speed));
                }
            }
            return;
        }

        // Sustained upward travel with no surface below is the real boat-fly
        // tell. Legit launches arc (they soon stop climbing), so we require a
        // large cumulative climb over several airborne ticks.
        if (dy > CLIMB_MIN_DY) {
            state.climbBuffer += dy;
        } else {
            state.climbBuffer = Math.max(0, state.climbBuffer - CLIMB_BUFFER_DECAY);
        }
        if (state.airTicks >= CLIMB_MIN_AIR_TICKS && state.climbBuffer >= CLIMB_TRIGGER) {
            int ticks = state.airTicks;
            state.airTicks = 0;
            state.climbBuffer = 0;
            flag(data, String.format("CLIMB DY:%.3f T:%d", dy, ticks));
        }

        // Fast-and-level airborne boats can sneak under the climb check, so an
        // extreme sustained horizontal speed is caught too.
        if (speed > EXTREME_AIR_SPEED) {
            state.speedBuffer += 1.0;
            if (state.speedBuffer >= AIR_SPEED_BUFFER) {
                state.speedBuffer = 0;
                flag(data, String.format("AIR S:%.3f", speed));
            }
        } else {
            state.speedBuffer = Math.max(0, state.speedBuffer - 0.25);
        }
    }

    private boolean hasSurfaceNearby(Location boatLoc) {
        double x = boatLoc.getX();
        double y = boatLoc.getY();
        double z = boatLoc.getZ();

        BoundingBox box = new BoundingBox(
            x - SURFACE_BOX_XZ, y - SKIM_BOX_DOWN, z - SURFACE_BOX_XZ,
            x + SURFACE_BOX_XZ, y + 1.0, z + SURFACE_BOX_XZ
        );

        World world = boatLoc.getWorld();
        int minX = (int) Math.floor(box.minX());
        int minY = (int) Math.floor(box.minY());
        int minZ = (int) Math.floor(box.minZ());
        int maxX = (int) Math.floor(box.maxX());
        int maxY = (int) Math.floor(box.maxY());
        int maxZ = (int) Math.floor(box.maxZ());

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Block block = world.getBlockAt(bx, by, bz);
                    if (block.getType().isCollidable() || isLiquid(block)) {
                        BoundingBox blockBox = new BoundingBox(bx, by, bz, bx + 1, by + 1, bz + 1);
                        if (box.intersects(blockBox)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private double surfaceMaxSpeed(Block at, Block below) {
        if (isIce(at.getType()) || isIce(below.getType())) {
            return (at.getType() == Material.BLUE_ICE || below.getType() == Material.BLUE_ICE)
                ? SPEED_BLUE_ICE : SPEED_ICE;
        }
        if (isLiquid(at) || isLiquid(below)) {
            return SPEED_WATER;
        }
        if (at.getType().isAir() && below.getType().isCollidable()) {
            return SPEED_GROUND;
        }
        return SPEED_LAND;
    }

    private String surfaceLabel(Block at, Block below) {
        if (isIce(at.getType()) || isIce(below.getType())) {
            return "ICE";
        }
        if (isLiquid(at) || isLiquid(below)) {
            return "WATER";
        }
        if (at.getType().isAir() && below.getType().isCollidable()) {
            return "GROUND";
        }
        return "LAND";
    }

    private boolean isLiquid(Block block) {
        Material type = block.getType();
        return type == Material.WATER || type == Material.LAVA;
    }

    private boolean isIce(Material mat) {
        return mat == Material.ICE || mat == Material.PACKED_ICE || mat == Material.BLUE_ICE || mat == Material.FROSTED_ICE;
    }

    private static class BoatState {
        int airTicks = 0;
        double climbBuffer = 0;
        double speedBuffer = 0;
        long lastIceMomentum = 0;
    }
}
