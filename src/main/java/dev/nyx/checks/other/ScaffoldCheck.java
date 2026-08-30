package dev.nyx.checks.other;

import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;
import dev.nyx.util.BoundingBox;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scaffold detection.
 *
 * This folds several heuristics into one configurable check with reliable,
 * low-false-positive signals:
 *
 *  1. PositionPlace - the clicked support face is not reachable/valid from the
 *     player's eye position (the classic scaffold "place under feet" signature).
 *  2. RotationPlace - raytrace from the player's eyes does not hit the clicked
 *     support block (client is placing without looking at the target).
 *  3. Place rate - multiple distinct blocks placed within a single movement tick
 *     (vanilla clients place at most one block per tick).
 *  4. InvalidPlaceFace/Cursor - impossible face id or non-finite cursor data.
 *
 * The block address the client clicks is the SUPPORT (block already present),
 * while the block actually placed is at support + face offset.
 */
@CheckData(name = "Scaffold", description = "Detects scaffold/tower auto-block placement")
public class ScaffoldCheck extends Check {

    private static final int VALID_FACE_MIN = 0;
    private static final int VALID_FACE_MAX = 5;
    private static final double MAX_REACH = 4.5;
    private static final double COLLISION_EXPANSION = 0.3;

    private final Map<UUID, ScaffoldState> stateMap = new ConcurrentHashMap<>();

    public ScaffoldCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return false;
    }

    @Override
    public void handle(NyxPlayerData data) {
        if (data.getPositionHistory().size() < 2) return;
        if (data.isInVehicle()) return;

        ScaffoldState state = stateMap.computeIfAbsent(data.getPlayer().getUniqueId(), k -> new ScaffoldState());
        int placeCount = data.getPlaceCountThisTick();

        if (placeCount > 1) {
            double sensitivity = getSensitivity(data);
            int allowed = sensitivity > 0.9 ? 2 : 1;
            if (placeCount > allowed) {
                flag(data, String.format("Rate %d/tick", placeCount));
                state.buffer = Math.max(0, state.buffer - 0.5);
                return;
            }
        }

        int face = data.getPlaceFace();
        if (face < VALID_FACE_MIN || face > VALID_FACE_MAX) {
            flag(data, "Face " + face);
            state.buffer = Math.max(0, state.buffer - 0.3);
            return;
        }

        int px = data.getPlaceBlockX();
        int py = data.getPlaceBlockY();
        int pz = data.getPlaceBlockZ();
        if (px == 0 && py == 0 && pz == 0 && data.getLastPlaceBlockX() == 0) {
            return;
        }

        BlockFace bf = faceToBlockFace(face);
        if (bf == null) return;

        // The block that was actually placed is support + face offset.
        int placedX = px + bf.getModX();
        int placedY = py + bf.getModY();
        int placedZ = pz + bf.getModZ();

        // A tower places blocks consistently beneath the feet; give the position
        // check a slight leniency so we don't false on legit builds while still
        // catching the "under feet with no visible face" signature.
        double camY = data.getPlayer().getLocation().getY() + 1.62;
        boolean targetBelowEye = placedY + 1 < camY - 0.0;

        double[] reach = {MAX_REACH};

        double sensitivity = getSensitivity(data);
        double tolerance = (1.0 - sensitivity) * 0.8;
        reach[0] += tolerance;

        BoundingBox support = blockBox(px, py, pz, false);
        BoundingBox supportExpanded = support.expand(COLLISION_EXPANSION, COLLISION_EXPANSION, COLLISION_EXPANSION);

        Location eye = data.getPlayer().getEyeLocation();
        double eyeX = eye.getX();
        double eyeY = eye.getY();
        double eyeZ = eye.getZ();
        Vector look = eye.getDirection();

        boolean inSupport = supportExpanded.contains(eyeX, eyeY, eyeZ);

        if (!inSupport) {
            double dist = raytraceDistance(eyeX, eyeY, eyeZ, look.getX(), look.getY(), look.getZ(), supportExpanded);
            boolean rayHits = dist != Double.MAX_VALUE && dist <= reach[0];

            int side = getVisibleSide(eyeX, eyeY, eyeZ, support);

            if (!rayHits) {
                // RotationPlace: cannot hit the support block from the looked direction.
                if (state.rotationMisses < 2) {
                    state.rotationMisses++;
                } else {
                    flag(data, String.format("Rot S:%d,%d,%d", px, py, pz));
                    state.buffer = Math.max(0, state.buffer - 0.4);
                    state.rotationMisses = 0;
                    return;
                }
            } else {
                state.rotationMisses = 0;
            }

            if (targetBelowEye && side == NONE_VISIBLE) {
                state.buffer++;
                if (state.buffer > 4) {
                    flag(data, String.format("Hidden S:%d,%d,%d F:%d", px, py, pz, face));
                    state.buffer = 0;
                }
            } else {
                state.buffer = Math.max(0, state.buffer - 0.15);
            }
        } else {
            state.buffer = Math.max(0, state.buffer - 0.15);
        }
    }

    private double getSensitivity(NyxPlayerData data) {
        var config = getConfig();
        return config != null ? config.sensitivity() : 0.7;
    }

    private static final int NONE_VISIBLE = -1;

    private int getVisibleSide(double ex, double ey, double ez, BoundingBox b) {
        if (ey > b.maxY()) return 1; // up
        if (ey < b.minY()) return 0; // down
        if (ex > b.maxX()) return 4; // east
        if (ex < b.minX()) return 3; // west
        if (ez > b.maxZ()) return 5; // south
        if (ez < b.minZ()) return 2; // north
        return NONE_VISIBLE;
    }

    private BoundingBox blockBox(int x, int y, int z, boolean all) {
        return new BoundingBox(x, y, z, x + 1, y + 1, z + 1);
    }

    private static BlockFace faceToBlockFace(int id) {
        return switch (id) {
            case 0 -> BlockFace.DOWN;
            case 1 -> BlockFace.UP;
            case 2 -> BlockFace.NORTH;
            case 3 -> BlockFace.SOUTH;
            case 4 -> BlockFace.WEST;
            case 5 -> BlockFace.EAST;
            default -> null;
        };
    }

    private double raytraceDistance(double ox, double oy, double oz,
                                     double dx, double dy, double dz,
                                     BoundingBox box) {
        double tmin = -Double.MAX_VALUE;
        double tmax = Double.MAX_VALUE;

        if (Math.abs(dx) < 1.0E-7) {
            if (ox < box.minX() || ox > box.maxX()) return Double.MAX_VALUE;
        } else {
            double t1 = (box.minX() - ox) / dx;
            double t2 = (box.maxX() - ox) / dx;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return Double.MAX_VALUE;
        }

        if (Math.abs(dy) < 1.0E-7) {
            if (oy < box.minY() || oy > box.maxY()) return Double.MAX_VALUE;
        } else {
            double t1 = (box.minY() - oy) / dy;
            double t2 = (box.maxY() - oy) / dy;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return Double.MAX_VALUE;
        }

        if (Math.abs(dz) < 1.0E-7) {
            if (oz < box.minZ() || oz > box.maxZ()) return Double.MAX_VALUE;
        } else {
            double t1 = (box.minZ() - oz) / dz;
            double t2 = (box.maxZ() - oz) / dz;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return Double.MAX_VALUE;
        }

        if (tmin < 0) {
            return Double.MAX_VALUE;
        }

        double ix = ox + dx * tmin;
        double iy = oy + dy * tmin;
        double iz = oz + dz * tmin;

        return Math.sqrt(
            (ix - ox) * (ix - ox) +
            (iy - oy) * (iy - oy) +
            (iz - oz) * (iz - oz)
        );
    }

    private static class ScaffoldState {
        double buffer = 0;
        int rotationMisses = 0;
    }
}
