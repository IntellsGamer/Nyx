package dev.nyx.checks.elytra;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;

@CheckData(name = "ElytraA", description = "Started gliding while already gliding")
public class ElytraACheck extends Check {

    public ElytraACheck(Nyx plugin) {
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
