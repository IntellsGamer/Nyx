package dev.idebugger.nyx.checks.combat;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;

@CheckData(name = "HitBox", description = "Detects attacks that completely miss the hitbox")
public class HitBoxCheck extends Check {

    public HitBoxCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return false;
    }

    @Override
    public void handle(NyxPlayerData data) {
    }
}
