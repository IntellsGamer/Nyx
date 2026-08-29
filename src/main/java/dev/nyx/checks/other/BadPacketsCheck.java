package dev.nyx.checks.other;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;

@CheckData(name = "BadPackets", description = "Detects invalid packet data and impossible values")
public class BadPacketsCheck extends Check {

    public BadPacketsCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void handle(NyxPlayerData data) {
        if (data.getPositionHistory().size() < 2) return;

        var current = data.getPositionHistory().peekFirst();
        if (current == null) return;

        float yaw = current.location().getYaw();
        float pitch = current.location().getPitch();

        if (Float.isNaN(yaw) || Float.isNaN(pitch)
            || Float.isInfinite(yaw) || Float.isInfinite(pitch)) {
            flag(data, "InvalidRotation");
            return;
        }

        if (pitch > 90.0f || pitch < -90.0f) {
            flag(data, String.format("ImpossiblePitch:%.1f", pitch));
            return;
        }

        double dx = data.getDeltaX();
        double dy = data.getDeltaY();
        double dz = data.getDeltaZ();

        if (Double.isNaN(dx) || Double.isNaN(dy) || Double.isNaN(dz)
            || Double.isInfinite(dx) || Double.isInfinite(dy) || Double.isInfinite(dz)) {
            flag(data, "InvalidDelta");
            return;
        }

        double speed = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (speed > 100) {
            flag(data, String.format("SpeedHack D:%.2f", speed));
            return;
        }

        if (data.isOnGround() && data.isLastOnGround()) {
            if (Math.abs(dy) > 1.0) {
                flag(data, String.format("InvalidGroundDY:%.4f", dy));
            }
        }

        var history = data.getPositionHistory();
        if (history.size() >= 2) {
            var first = history.peekFirst();
            var second = history.stream().skip(1).findFirst().orElse(null);
            if (first != null && second != null) {
                double firstX = first.location().getX();
                double firstZ = first.location().getZ();
                double secondX = second.location().getX();
                double secondZ = second.location().getZ();
                if (firstX == secondX && firstZ == secondZ && data.getHorizontalSpeed() > 0.1) {
                    flag(data, "NoDeltaUpdate");
                }
            }
        }
    }
}
