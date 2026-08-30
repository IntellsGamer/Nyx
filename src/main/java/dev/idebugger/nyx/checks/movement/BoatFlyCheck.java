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

@CheckData(name = "BoatFly", description = "Detects flying or speed-hacking with boats")
public class BoatFlyCheck extends Check {

    private static final double SPEED_LAND = 0.22;
    private static final double SPEED_WATER = 0.60;
    private static final double SPEED_ICE = 2.60;
    private static final double SPEED_BLUE_ICE = 4.40;
    private static final double AIR_FLAG_SPEED = 0.45;
    private static final double AIR_FLAG_BUFFER = 4.0;
    private static final long ENTER_GRACE_MS = 3000;

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

        if (!isBoatNearGround(boatLoc)) {
            double speed = data.getHorizontalSpeed();
            if (speed > AIR_FLAG_SPEED) {
                state.airBuffer += 1.0;
                if (state.airBuffer >= AIR_FLAG_BUFFER) {
                    state.airBuffer = 0;
                    flag(data, String.format("AIR S:%.3f", speed));
                }
            } else {
                state.airBuffer = Math.max(0, state.airBuffer - 0.25);
            }
            return;
        }
        state.airBuffer = 0;

        double speed = data.getHorizontalSpeed();
        if (speed < 0.01) return;

        double maxSpeed;
        String surface;

        if (isIce(at.getType()) || isIce(below.getType())) {
            if (at.getType() == Material.BLUE_ICE || below.getType() == Material.BLUE_ICE) {
                maxSpeed = SPEED_BLUE_ICE;
                surface = "BLUE_ICE";
            } else if (at.getType() == Material.PACKED_ICE || below.getType() == Material.PACKED_ICE) {
                maxSpeed = SPEED_ICE;
                surface = "PACKED_ICE";
            } else {
                maxSpeed = SPEED_ICE;
                surface = "ICE";
            }
        } else if (at.getType() == Material.WATER || below.getType() == Material.WATER) {
            maxSpeed = SPEED_WATER;
            surface = "WATER";
        } else if (at.getType().isAir() && below.getType().isCollidable()) {
            maxSpeed = SPEED_WATER;
            surface = "SURFACE";
        } else {
            maxSpeed = SPEED_LAND;
            surface = "LAND";
        }

        if (speed > maxSpeed) {
            flag(data, String.format("S:%.3f M:%.3f %s", speed, maxSpeed, surface));
        }
    }

    private boolean isBoatNearGround(Location boatLoc) {
        double x = boatLoc.getX();
        double y = boatLoc.getY();
        double z = boatLoc.getZ();

        BoundingBox box = new BoundingBox(
            x - 0.8, y - 3.0, z - 0.8,
            x + 0.8, y + 1.0, z + 0.8
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

    private boolean isLiquid(Block block) {
        Material type = block.getType();
        return type == Material.WATER || type == Material.LAVA;
    }

    private boolean isIce(Material mat) {
        return mat == Material.ICE || mat == Material.PACKED_ICE || mat == Material.BLUE_ICE || mat == Material.FROSTED_ICE;
    }

    private static class BoatState {
        double airBuffer = 0;
    }
}