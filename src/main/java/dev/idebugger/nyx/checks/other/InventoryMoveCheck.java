package dev.idebugger.nyx.checks.other;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;
import org.bukkit.event.inventory.InventoryType;

@CheckData(name = "InventoryMove", description = "Detects movement while inventory is open")
public class InventoryMoveCheck extends Check {

    public InventoryMoveCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void handle(NyxPlayerData data) {
        if (data.getPositionHistory().size() < 2) return;

        var player = data.getPlayer();

        if (!player.isOnline()) return;

        if (player.getOpenInventory() == null) return;
        if (player.getOpenInventory().getType() == InventoryType.CRAFTING
            || player.getOpenInventory().getType() == InventoryType.CREATIVE) {
            return;
        }

        double speed = data.getHorizontalSpeed();
        if (speed < 0.01) return;

        boolean onGround = data.isOnGround();
        boolean lastOnGround = data.isLastOnGround();

        if (!onGround && !lastOnGround) {
            return;
        }

        double maxSpeed = 0.05;

        if (speed > maxSpeed) {
            flag(data, String.format(
                "S:%.4f MAX:%.2f INV:%s",
                speed, maxSpeed,
                player.getOpenInventory().getType().name()
            ));
        }
    }
}
