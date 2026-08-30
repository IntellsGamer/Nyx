package dev.idebugger.nyx.checks.movement;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;
import dev.idebugger.nyx.data.NyxPlayerData.IceType;

@CheckData(name = "Speed", description = "Detects horizontal speed violations")
public class SpeedCheck extends Check {

    private static final long FIREWORK_GRACE_MS = 4000;
    private static final long GLIDE_GRACE_MS = 3000;

    public SpeedCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void handle(NyxPlayerData data) {
        if (data.getPositionHistory().size() < 2) return;
        if (data.isInVehicle()) return;

        if (data.isGliding() || data.isWasGliding()) return;

        long now = System.currentTimeMillis();
        if (now - data.getLastFireworkTime() < FIREWORK_GRACE_MS) return;
        if (now - data.getLastGlideTime() < GLIDE_GRACE_MS) return;

        double speed = data.getHorizontalSpeed();
        if (speed < 0.01) return;

        double max = getMaxSpeed(data);
        if (speed > max) {
            flag(data, String.format("S:%.3f M:%.3f", speed, max));
        }
    }

    private double getMaxSpeed(NyxPlayerData data) {
        if (data.isInWater()) return 0.35;
        if (data.isInLava()) return 0.30;
        if (data.isClimbing()) return 0.17;

        // Sprint-jumping (or walking) on ice legitimately carries very high horizontal
        // momentum for a short while after leaving the surface. Without this, the plain
        // 0.45 airborne cap false-flags anyone sprint-jumping across ice/packed/blue ice.
        if (!data.isOnGround() && !data.isLastOnGround() && data.hasIceMomentum()) {
            IceType ice = data.getLastIceType();
            return switch (ice) {
                case BLUE_ICE -> 2.6;
                case PACKED_ICE -> 1.8;
                default -> 1.6; // ICE, FROSTED_ICE
            };
        }

        if (data.isOnGround() || data.isLastOnGround()) {
            IceType ice = data.getIceType();
            if (ice != null && ice != IceType.NONE) return ice.getMaxSpeed();
            if (data.isOnSlime()) return 0.40;
            if (data.isOnSoulSand()) return 0.20;
            if (data.isSneaking()) return 0.10;
            if (data.isSprinting()) return 0.35;
            return 0.28;
        }

        return 0.45;
    }
}