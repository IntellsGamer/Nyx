package dev.nyx.checks.combat;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;
import dev.nyx.util.BoundingBox;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Collection;

@CheckData(name = "Reach", description = "Detects extended reach using raytracing")
public class ReachCheck extends Check {

    private static final double BASE_REACH = 3.0;
    private static final double MAX_REACH = 6.0;
    private static final double SEARCH_RADIUS = 8.0;
    private static final double HITBOX_EXPANSION = 0.4;
    private static final double MAX_PING_COMPENSATION = 1.0;

    public ReachCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return false;
    }

    @Override
    public void runAsync(NyxPlayerData data) {
        if (!canRun(data)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> handle(data));
    }

    @Override
    public void handle(NyxPlayerData data) {
        Player player = data.getPlayer();
        int targetId = data.getLastAttackedEntityId();
        if (targetId == -1) return;

        long lastAttack = data.getLastAttackTime();
        long now = System.currentTimeMillis();
        if (now - lastAttack > 100) return;

        data.setLastAttackedEntityId(-1);

        Location eyeLoc = player.getEyeLocation();
        Collection<Entity> nearby = player.getNearbyEntities(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS);
        Entity target = null;
        for (Entity e : nearby) {
            if (e.getEntityId() == targetId && e instanceof LivingEntity && !e.equals(player)) {
                target = e;
                break;
            }
        }
        if (target == null) return;

        Vector lookDir = eyeLoc.getDirection();

        BoundingBox targetBox = BoundingBox.fromEntity(target);
        double hitboxExpansion = HITBOX_EXPANSION;
        targetBox = targetBox.expand(hitboxExpansion, hitboxExpansion, hitboxExpansion);

        double eyeX = eyeLoc.getX();
        double eyeY = eyeLoc.getY();
        double eyeZ = eyeLoc.getZ();
        double dirX = lookDir.getX();
        double dirY = lookDir.getY();
        double dirZ = lookDir.getZ();

        double distance = raytraceDistance(eyeX, eyeY, eyeZ, dirX, dirY, dirZ, targetBox);

        if (distance == Double.MAX_VALUE) {
            if (targetBox.contains(eyeX, eyeY, eyeZ)) {
                distance = 0;
            } else {
                HitBoxCheck hitbox = Nyx.get().getCheckManager().getCheck(HitBoxCheck.class);
                if (hitbox != null) hitbox.flag(data, "MISS T:" + targetId);
                return;
            }
        }

        int ping = data.getPing();
        double pingCompensation = Math.min(ping / 100.0 * 0.1, MAX_PING_COMPENSATION);

        double maxReach = BASE_REACH + pingCompensation;

        if (player.isSprinting()) {
            maxReach = Math.max(maxReach, BASE_REACH + 0.2 + pingCompensation);
        }

        double sensitivity = getConfig() != null ? getConfig().sensitivity() : 0.8;
        double tolerance = (1.0 - sensitivity) * 0.3;
        maxReach += tolerance;

        if (distance > maxReach) {
            double excess = distance - maxReach;
            flag(data, String.format(
                "D:%.2f MAX:%.2f E:%.2f P:%d",
                distance, maxReach, excess, ping
            ));
        }
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
}
