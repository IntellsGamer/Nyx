package dev.nyx.checks.combat;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;

@CheckData(name = "NoSwing", description = "Detects attacks without a swing animation")
public class NoSwingCheck extends Check {

    public NoSwingCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return false;
    }

    @Override
    public void handle(NyxPlayerData data) {
        if (data.isSentAttackThisTick() && !data.isSentAnimationThisTick()) {
            flag(data, "NoSwing");
        }
    }
}
