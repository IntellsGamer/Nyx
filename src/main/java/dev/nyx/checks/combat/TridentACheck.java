package dev.nyx.checks.combat;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;

@CheckData(name = "TridentA", description = "Attempted riptide while not in water")
public class TridentACheck extends Check {

    public TridentACheck(Nyx plugin) {
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
