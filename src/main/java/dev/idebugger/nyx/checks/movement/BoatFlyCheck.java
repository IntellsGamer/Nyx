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
 * Detects "boat fly": making a boat climb or sprint through the air without a
 * surface underneath. Boating is naturally bouncy — boats ride waves, bobble at
 * the surface and can briefly lift a block — so the check separates legit
 * surfacing from actual flight:
 *
 *  - "on surface" only counts a solid/liquid within ~1 block under the boat, so
 *    hovering a few blocks above water is genuinely airborne;
 *  - water/land caps are moderate, ice caps are high;
 *  - high ice momentum is allowed to carry on to water/land only while it is
 *    still fresh — you cannot outrun a plain water boat forever;
 *  - airborne, you must either be clearly falling or have recently left ice,
 *    otherwise sustained climbing or even a moderate level-hold flight flags.
 */
@CheckData(name = "BoatFly", description = "Detects flying or speed-hacking with boats")
public class BoatFlyCheck extends Check {

    private static final long ENTER_GRACE_MS = 3000;
    private static final long ICE_MOMENTUM_MS = 2500;

    private static final double SKIM_BOX_DOWN = 1.0;
    private static final double SKIM_BOX_UP = 0.3;
    private static final double SURFACE_BOX_XZ = 0.9;

    // —— Airborne signals ——
    private static final double CLIMB_MIN_DY = 0.02;
    private static final double CLIMB_TRIGGER = 0.5;
    private static final int CLIMB_MIN_AIR_TICKS = 6;
    private static final double CLIMB_BUFFER_DECAY = 0.10;
    private static final double HOVER_AIR_SPEED = 1.6;
    private static final double FALL_AIR_SPEED = 2.6;
    private static final double FALL_DY = -0.35;
    private static final double ICE_AIR_SPEED = 6.0;
    private static final double AIR_SPEED_BUFFER = 8.0;

    // —— On-surface caps (blocks/tick) ——
    private static final double SPEED_LAND = 1.0;
    private static final double SPEED_WATER = 1.0;
    private static final double SPEED_GROUND = 1.1;
    private static final double SPEED_ICE = 4.0;
    private static final double SPEED_BLUE_ICE = 6.5;
    /** Fresh-off-ice momentum caps while back on water/land: ice → 2.2, packed → 3.0, blue → 4.0. */
    private static final double MOMENTUM_ICE = 2.2;
    private static final double MOMENTUM_PACKED_ICE = 3.0;
    private static final double MOMENTUM_BLUE_ICE = 4.0;
    private static final double SURFACE_SPEED_BUFFER = 2.0;
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
            if (isIce(at.getType()) && isIce(below.getType())) {
                state.lastIceType = at.getType();
            } else if (isIce(at.getType())) {
                state.lastIceType = at.getType();
            } else {
                state.lastIceType = below.getType();
            }
        }
        boolean iceMomentum = System.currentTimeMillis() - state.lastIceMomentum < ICE_MOMENTUM_MS;

        if (hasSurfaceNearby(boatLoc)) {
            // Boat is rafting on a liquid or resting on solid ground/ice within
            // ~1 block below. If it rides higher than that it is airborne.
            state.airTicks = 0;
            state.climbBuffer = 0;
            state.speedBuffer = Math.max(0, state.speedBuffer - SURFACE_SPEED_DECAY);

            double max = surfaceMaxSpeed(at, below, state, iceMomentum);
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

        // True airborne boat: no solid/liquid within the skim box.
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
        // cumulative climb over several airborne ticks.
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
            return;
        }

        // Level "flight": not falling, no surface below — the boat is being
        // held in the air. Flag once it sustains a moderate speed. A genuinely
        // falling boat (off a launcher/cliff) can only be flagged at absurd
        // speeds, since falling legitimately carries momentum.
        boolean falling = dy < FALL_DY;
        double airMax = falling ? FALL_AIR_SPEED : HOVER_AIR_SPEED;
        if (speed > airMax) {
            state.speedBuffer += 1.0;
            if (state.speedBuffer >= AIR_SPEED_BUFFER) {
                state.speedBuffer = 0;
                flag(data, String.format("AIR S:%.3f M:%.3f", speed, airMax));
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
            x + SURFACE_BOX_XZ, y + SKIM_BOX_UP, z + SURFACE_BOX_XZ
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

    private double surfaceMaxSpeed(Block at, Block below, BoatState state, boolean iceMomentum) {
        if (isIce(at.getType()) || isIce(below.getType())) {
            return (at.getType() == Material.BLUE_ICE || below.getType() == Material.BLUE_ICE)
                ? SPEED_BLUE_ICE : SPEED_ICE;
        }
        if (iceMomentum) {
            // Fresh ice momentum keeps carrying the boat fast even over water or
            // ground, but it is a short-lived bonus, not a permanent free pass.
            return switch (state.lastIceType) {
                case BLUE_ICE -> MOMENTUM_BLUE_ICE;
                case PACKED_ICE -> MOMENTUM_PACKED_ICE;
                default -> MOMENTUM_ICE;
            };
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
        Material lastIceType = Material.ICE;
    }
}