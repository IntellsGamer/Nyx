package dev.nyx.checks.combat;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;
import org.bukkit.util.Vector;

@CheckData(name = "Velocity", description = "Detects anti-knockback modifications")
public class VelocityCheck extends Check {

    private static final long VELOCITY_TIMEOUT_MS = 5000;
    private static final double MIN_HORIZ_RATIO = 0.75;
    private static final double MIN_VERT_RATIO = 0.75;
    private static final double MAX_HORIZ_RATIO = 1.8;

    public VelocityCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void handle(NyxPlayerData data) {
        Vector appliedVelocity = data.getVelocity();
        long velocityTime = data.getLastVelocityTime();

        if (appliedVelocity == null || appliedVelocity.lengthSquared() < 0.0001) return;
        if (System.currentTimeMillis() - velocityTime > VELOCITY_TIMEOUT_MS) return;

        double expectedHoriz = Math.hypot(appliedVelocity.getX(), appliedVelocity.getZ());
        double expectedVert = appliedVelocity.getY();

        double actualHoriz = data.getHorizontalSpeed();
        double actualVert = data.getDeltaY();

        if (expectedHoriz > 0.01) {
            double ratio = Math.min(actualHoriz / expectedHoriz, MAX_HORIZ_RATIO);
            if (ratio < MIN_HORIZ_RATIO) {
                flag(data, String.format(
                    "H:%.0f%% E:%.4f A:%.4f",
                    ratio * 100, expectedHoriz, actualHoriz
                ));
            }
        }

        if (expectedVert > 0.01) {
            if (data.isOnGround()) {
                if (Math.abs(actualVert) > 0.01) return;
            }
            double vertRatio = Math.min(actualVert / expectedVert, MAX_HORIZ_RATIO);
            if (vertRatio < MIN_VERT_RATIO && !data.isOnGround()) {
                flag(data, String.format(
                    "V:%.0f%% E:%.4f A:%.4f",
                    vertRatio * 100, expectedVert, actualVert
                ));
            }
        }

        data.setVelocity(new Vector(0, 0, 0));
    }
}
