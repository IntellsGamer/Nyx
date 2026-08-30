package dev.idebugger.nyx.checks.movement;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;
import org.bukkit.potion.PotionEffectType;

@CheckData(name = "Fly", description = "Detects flight, air jump, and hover exploits")
public class FlyCheck extends Check {

    private static final double AIR_DRAG = 0.98;
    private static final double GRAVITY = 0.08;
    private static final double TOLERANCE = 0.05;
    private static final int MAX_AIR_ASCENDING_TICKS = 10;

    public FlyCheck(Nyx plugin) {
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
        if (data.getPositionHistory().size() < 3) return;

        if (data.isGliding()) return;
        if (data.isInVehicle()) return;
        if (data.getPlayer().isFlying()) return;
        if (data.isInWater() || data.isInLava()) return;
        if (data.isInWeb()) return;
        if (data.isClimbing()) return;
        if (data.getPlayer().hasPotionEffect(PotionEffectType.SLOW_FALLING)) return;

        if (data.isOnGround()) return;

        double deltaY = data.getDeltaY();
        double lastDeltaY = data.getLastDeltaY();
        int airTicks = data.getServerAirTicks();

        if (Math.abs(deltaY) < 0.001) return;

        if (airTicks == 1) {
            if (deltaY > 0.5) {
                flag(data, String.format("Ascend DY:%.4f T:%d", deltaY, airTicks));
            }
            return;
        }

        if (deltaY > 0.001) {
            if (airTicks > MAX_AIR_ASCENDING_TICKS) {
                flag(data, String.format("Ascend DY:%.4f T:%d", deltaY, airTicks));
            }
            return;
        }

        double expected = lastDeltaY * AIR_DRAG - GRAVITY;
        double diff = Math.abs(deltaY - expected);

        if (diff > TOLERANCE) {
            flag(data, String.format("DY:%.4f EX:%.4f D:%.4f", deltaY, expected, diff));
        }
    }
}
