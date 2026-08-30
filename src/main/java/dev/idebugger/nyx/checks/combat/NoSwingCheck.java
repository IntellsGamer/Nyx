package dev.idebugger.nyx.checks.combat;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;

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
