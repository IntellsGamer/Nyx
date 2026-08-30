package dev.idebugger.nyx.checks.vehicle;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;

@CheckData(name = "EntitySpeed", description = "Impossible vehicle input values")
public class EntitySpeedCheck extends Check {

    public EntitySpeedCheck(Nyx plugin) {
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
