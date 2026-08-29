package dev.nyx.checks.combat;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;
import dev.nyx.data.NyxPlayerData.RotationSnapshot;

@CheckData(name = "AimModulo360", description = "Detects modulo-360 yaw snapping")
public class AimModulo360Check extends Check {

    public AimModulo360Check(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return false;
    }

    @Override
    public void handle(NyxPlayerData data) {
        var history = data.getRotationHistory();
        if (history.size() < 2) return;

        RotationSnapshot current = history.peekFirst();
        float yaw = current.yaw();

        if (yaw < 360 && yaw > -360) {
            float deltaYaw = data.getLastDeltaYaw();
            float lastDeltaYaw = data.getLastDeltaYaw();

            if (Math.abs(deltaYaw) > 320 && Math.abs(lastDeltaYaw) < 30) {
                flag(data, String.format("DY:%.1f LDY:%.1f", deltaYaw, lastDeltaYaw));
            }
        }
    }
}
