package dev.idebugger.nyx.checks.combat;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;

@CheckData(name = "MultiInteract", description = "Detects attacking multiple entities per tick")
public class MultiInteractCheck extends Check {

    public MultiInteractCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return false;
    }

    @Override
    public void handle(NyxPlayerData data) {
        if (data.getInteractedEntitiesThisTick() > 1) {
            flag(data, String.format("Entities:%d", data.getInteractedEntitiesThisTick()));
        }
    }
}
