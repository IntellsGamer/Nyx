package dev.idebugger.nyx.checks.combat;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;
import dev.idebugger.nyx.util.BoundingBox;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KillAura detection with two server-verifiable signals:
 *
 *  1. ThroughWall — every line from the attacker's eyes to the target's
 *     hitbox passes through solid blocks. A vanilla client cannot attack
 *     what it cannot see; an aura locks onto targets through cover. This is
 *     the most blatant aura signature and was previously undetectable.
 *
 *  2. ViewCone — the direction from eyes to target is far outside the
 *     attacker's look direction. Vanilla players hit within ~60 degrees of
 *     where they look (hitbox expansion + lag); aura clients snap onto
 *     targets regardless of facing.
 *
 * Both signals are evaluated at attack time against the current server state
 * (a lag-compensated rewind buffer is the planned next step — see
 * NYX_AUDIT_AND_UPGRADE_PLAN.md §2.2), so thresholds are generous and a
 * sustained buffer is required before flagging.
 */
@CheckData(name = "KillAura", description = "Detects killaura via wall-hits and view-cone violations")
public class KillAuraCheck extends Check {

    private static final double SEARCH_RADIUS = 6.0;
    private static final double MAX_VIEW_ANGLE_DEG = 75.0;
    private static final double MIN_TARGET_DISTANCE = 0.3;

    // Sustained violations before a flag: one hit through a corner during a
    // lag spike is noise; a sustained pattern inside a short window is not.
    private static final double FLAG_BUFFER = 3.0;
    private static final double BUFFER_DECAY = 0.25;
    private static final double WALL_WEIGHT = 1.0;
    private static final double ANGLE_WEIGHT = 0.75;

    // Voxel step for the occlusion march.
    private static final double STEP = 0.4;

    private final Map<UUID, State> stateMap = new ConcurrentHashMap<>();

    public KillAuraCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public void onPlayerQuit(UUID uuid) {
        stateMap.remove(uuid);
    }

    @Override
    public boolean isMovementCheck() {
        return false;
    }

    @Override
    public void handle(NyxPlayerData data) {
        // Driven from the attack path (handleAttack) in the packet listener;
        // the per-tick sweep has nothing to evaluate without an attack.
    }

    /**
     * Called by the packet listener when the player attacks an entity.
     * Must run on the player's region thread (the listener defers).
     */
    public void handleAttack(NyxPlayerData data, Player player, int targetId) {
        State state = stateMap.computeIfAbsent(player.getUniqueId(), k -> new State());
        state.decayAll();

        Entity target = findTarget(player, targetId);
        if (target == null) return;

        Location eye = player.getEyeLocation();
        Vector look = eye.getDirection().normalize();
        Vector eyeV = eye.toVector();

        BoundingBox box = BoundingBox.fromEntity(target).expand(0.1, 0.1, 0.1);
        Vector center = new Vector(
            (box.minX() + box.maxX()) / 2.0,
            (box.minY() + box.maxY()) / 2.0,
            (box.minZ() + box.maxZ()) / 2.0
        );
        Vector toTarget = center.clone().subtract(eyeV);
        double distance = toTarget.length();
        if (distance < MIN_TARGET_DISTANCE) return;
        toTarget.normalize();

        // --- Signal 1: fully behind cover ---
        if (isOccluded(eyeV, center, box, eye.getWorld())) {
            state.buffer += WALL_WEIGHT;
            state.lastInfo = String.format("Wall D:%.2f", distance);
        }

        // --- Signal 2: attack far outside the view cone ---
        double angleDeg = Math.toDegrees(Math.acos(
            Math.max(-1.0, Math.min(1.0, look.dot(toTarget)))));
        if (angleDeg > MAX_VIEW_ANGLE_DEG) {
            state.buffer += ANGLE_WEIGHT;
            state.lastInfo = String.format("Angle:%.0f D:%.2f", angleDeg, distance);
        }

        if (state.buffer >= FLAG_BUFFER) {
            state.buffer = 0;
            flag(data, state.lastInfo);
        }
    }

    private Entity findTarget(Player player, int targetId) {
        for (Entity e : player.getNearbyEntities(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS)) {
            if (e.getEntityId() == targetId && e instanceof LivingEntity && !e.equals(player)) {
                return e;
            }
        }
        return null;
    }

    /**
     * True when every straight line from the eyes to the target's box passes
     * through a solid block. Sampling multiple points on the box means a
     * target merely peeking from behind a corner still has one visible
     * sample point and is not falsely fully-occluded; only a completely
     * covered target counts.
     */
    private boolean isOccluded(Vector eye, Vector center, BoundingBox box, org.bukkit.World world) {
        double inset = 0.15;
        Vector[] samples = {
            center,
            new Vector(box.minX() + inset, center.getY(), center.getZ()),
            new Vector(box.maxX() - inset, center.getY(), center.getZ()),
            new Vector(center.getX(), center.getY(), box.minZ() + inset),
            new Vector(center.getX(), center.getY(), box.maxZ() - inset),
            new Vector(center.getX(), box.minY() + inset, center.getZ()),
        };
        int blocked = 0;
        for (Vector sample : samples) {
            if (segmentBlocked(eye, sample, world)) blocked++;
        }
        return blocked == samples.length;
    }

    /** Marches the segment in small steps; true if any solid block covers it. */
    private boolean segmentBlocked(Vector from, Vector to, org.bukkit.World world) {
        Vector dir = to.clone().subtract(from);
        double length = dir.length();
        if (length < 0.5) return false;
        dir.multiply(1.0 / length);

        // Stop short of the target box: the final stretch is inside the
        // hitbox sample point we aimed at.
        double limit = length - 0.45;
        for (double d = 0.3; d < limit; d += STEP) {
            double px = from.getX() + dir.getX() * d;
            double py = from.getY() + dir.getY() * d;
            double pz = from.getZ() + dir.getZ() * d;
            if (world.getBlockAt(
                    (int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz))
                    .getType().isSolid()) {
                return true;
            }
        }
        return false;
    }

    private static final class State {
        double buffer;
        String lastInfo = "";

        void decayAll() {
            buffer = Math.max(0, buffer - BUFFER_DECAY);
        }
    }
}
