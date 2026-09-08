package dev.idebugger.nyx.checks.other;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;

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
            // Deque iterator instead of stream().skip(): this runs per packet.
            var it = history.iterator();
            it.next();
            if (it.hasNext()) {
                var second = it.next();
                if (first != null && second != null) {
                    long dt = first.timestamp() - second.timestamp();
                    // The old condition (identical X/Z AND horizontalSpeed >
                    // 0.1) was self-contradictory — the speed is computed from
                    // those same deltas, so it was always exactly 0 here and
                    // the branch could never fire. What is actually suspicious
                    // is a frozen position-claim that persists far longer
                    // than a vanilla client ever stalls (e.g. a desynced
                    // NoPos-check claiming onGround at a stale spot).
                    boolean frozenXZ = first.location().getX() == second.location().getX()
                        && first.location().getZ() == second.location().getZ()
                        && first.location().getY() == second.location().getY();
                    if (frozenXZ && dt > 1_500_000_000L) {
                        flag(data, String.format("FrozenPos T:%dms", dt / 1_000_000L));
                    }
                }
            }
        }
    }
}
