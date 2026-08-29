package dev.nyx.checks.combat;

import dev.nyx.Nyx;
import dev.nyx.checks.Check;
import dev.nyx.checks.CheckData;
import dev.nyx.data.NyxPlayerData;
import org.bukkit.entity.Player;

@CheckData(name = "AttackWhileUsing", description = "Detects attacking while using an item")
public class AttackWhileUsingCheck extends Check {

    public AttackWhileUsingCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return false;
    }

    @Override
    public void handle(NyxPlayerData data) {
        if (!data.isSentAttackThisTick()) return;

        Player player = data.getPlayer();
        if (!player.isHandRaised()) return;

        if (player.isBlocking()) {
            flag(data, "BLOCKING");
        } else {
            flag(data, "USING_ITEM");
        }
    }
}
