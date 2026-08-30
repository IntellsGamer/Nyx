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
 *
 * Both sustained walking and bunny-hopping across the snow are covered: a
 * walk accumulates consecutive on-ground ticks over the block, while hopping
 * (short air gaps) is caught by counting each distinct landing instead.
 */
@CheckData(name = "SnowShoe", description = "Detects walking on powder snow without leather boots")
public class SnowShoeCheck extends Check {

    private static final int FLAG_AFTER_CONSECUTIVE_TICKS = 3;
    private static final int FLAG_AFTER_CONTACTS = 4;

    // A riptide launch loops over terrain (snow included) at high speed; keep
    // the flight path exempt from powder-snow checks until the boost fades.
    private static final long RIPTIDE_GRACE_MS = 4000;

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
        if (System.currentTimeMillis() - data.getLastRiptideTime() < RIPTIDE_GRACE_MS) return;

        if (!feetOverPowderSnow(player) || wearsLeatherBoots(player)) {
            // Off the snow or legitimately booted: fully fresh slate.
            data.setSnowShoeTicks(0);
            data.setSnowShoeContacts(0);
            data.setSnowShoePrevBad(false);
            return;
        }

        if (!data.isOnGround()) {
            // Mid-hop: the ticket counter resets but contact memory survives so
            // jumping onto/over the snow repeatedly still accumulates.
            data.setSnowShoeTicks(0);
            data.setSnowShoePrevBad(false);
            return;
        }

        int ticks = data.getSnowShoeTicks() + 1;
        data.setSnowShoeTicks(ticks);

        if (!data.isSnowShoePrevBad()) {
            data.setSnowShoeContacts(data.getSnowShoeContacts() + 1);
        }
        data.setSnowShoePrevBad(true);

        if (ticks >= FLAG_AFTER_CONSECUTIVE_TICKS
            || data.getSnowShoeContacts() >= FLAG_AFTER_CONTACTS) {
            flag(data, String.format("T:%d C:%d",
                ticks, data.getSnowShoeContacts()));
            data.setSnowShoeTicks(0);
        }
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