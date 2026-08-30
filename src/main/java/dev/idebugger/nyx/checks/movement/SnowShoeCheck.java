package dev.idebugger.nyx.checks.movement;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Detects walking on top of powder snow without the boots that make it
 * walkable. Clients that spoof leather-boot behavior (making the block solid
 * at its top face) leave footprints identical to a legit boot wearer, except
 * one thing the server can verify: the armour slot. Powder snow has no
 * collision shape server-side, so an on-ground player hovering above it
 * without leather boots is impossible in vanilla and always the exploit.
 */
@CheckData(name = "SnowShoe", description = "Detects walking on powder snow without leather boots")
public class SnowShoeCheck extends Check {

    private static final int FLAG_AFTER_TICKS = 3;

    public SnowShoeCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void handle(NyxPlayerData data) {
        if (data.isGliding()) return;
        if (data.isInVehicle()) return;

        Player player = data.getPlayer();
        if (player.isFlying()) return;

        if (!feetOverPowderSnow(player)) {
            data.setSnowShoeTicks(0);
            return;
        }

        if (wearsLeatherBoots(player)) {
            data.setSnowShoeTicks(0);
            return;
        }

        if (!data.isOnGround()) {
            data.setSnowShoeTicks(0);
            return;
        }

        int ticks = data.getSnowShoeTicks() + 1;
        data.setSnowShoeTicks(ticks);

        if (ticks < FLAG_AFTER_TICKS) return;

        flag(data, String.format("Ticks:%d", ticks));
    }

    private boolean feetOverPowderSnow(Player player) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int y = (int) Math.floor(loc.getY() - 0.001);
        int z = loc.getBlockZ();

        Block at = world.getBlockAt(x, y, z);
        if (at.getType() == Material.POWDER_SNOW) return true;

        Block below = world.getBlockAt(x, y - 1, z);
        return below.getType() == Material.POWDER_SNOW;
    }

    private boolean wearsLeatherBoots(Player player) {
        ItemStack boots = player.getInventory().getBoots();
        return boots != null && boots.getType() == Material.LEATHER_BOOTS;
    }
}