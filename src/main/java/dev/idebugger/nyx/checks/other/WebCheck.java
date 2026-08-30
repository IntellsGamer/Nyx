package dev.idebugger.nyx.checks.other;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;

@CheckData(name = "Web", description = "Detects moving through cobwebs too quickly")
public class WebCheck extends Check {

    private static final double WALK_IN_WEB = 0.054;
    private static final double SPRINT_IN_WEB = 0.070;
    private static final double WEAVING_MULT = 2.0;

    public WebCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void handle(NyxPlayerData data) {
        if (!data.isInWeb()) return;
        if (data.isInVehicle()) return;
        if (data.isInWater()) return;
        if (data.isInLava()) return;

        double maxBase = data.isClientSprinting() ? SPRINT_IN_WEB : WALK_IN_WEB;

        double threshold = maxBase;
        if (data.getPlayer().hasPotionEffect(org.bukkit.potion.PotionEffectType.WEAVING)) {
            threshold *= WEAVING_MULT;
        }

        double speed = data.getHorizontalSpeed();
        if (speed > threshold) {
            flag(data, String.format("S:%.4f MAX:%.4f", speed, threshold));
        }
    }
}
