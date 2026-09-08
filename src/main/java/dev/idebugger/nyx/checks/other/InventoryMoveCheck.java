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

        // The vanilla client stops *input* while a container is open, but the
        // player keeps sliding on momentum for a couple of ticks (sprint
        // decays 0.35 -> 0.15 -> 0.05). A flat 0.05 cap flagged everyone who
        // opened their inventory mid-run, twice. Allow a short grace of
        // decaying momentum: within the first ticks after movement stops,
        // the cap scales down from the last speed instead of being flat.
        double lastSpeed = Math.hypot(data.getLastDeltaX(), data.getLastDeltaZ());
        long now = System.currentTimeMillis();
        long sinceMove = now - Math.max(data.getLastAttackTime(), data.getLastRightClickTime());
        double maxSpeed = 0.05;
        if (lastSpeed > 0.13 && sinceMove > 50) {
            // Only decay-based tolerance for the immediate slide-out ticks.
            maxSpeed = Math.max(0.05, lastSpeed * 0.55);
        }

        if (speed > maxSpeed) {
            flag(data, String.format(
                "S:%.4f MAX:%.2f INV:%s",
                speed, maxSpeed,
                player.getOpenInventory().getType().name()
            ));
        }
    }
}
