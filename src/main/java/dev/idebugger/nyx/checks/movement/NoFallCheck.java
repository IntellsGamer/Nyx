package dev.idebugger.nyx.checks.movement;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.checks.CheckData;
import dev.idebugger.nyx.data.NyxPlayerData;
import dev.idebugger.nyx.util.BoundingBox;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

@CheckData(name = "NoFall", description = "Detects fall damage mitigation exploits")
public class NoFallCheck extends Check {

    private static final double MIN_FALL_DELTA = -0.03;
    private static final double GROUND_BOX_EXPAND = 0.3;
    private static final double WURST_TRACK_THRESHOLD = 0.3;

    public NoFallCheck(Nyx plugin) {
        super(plugin);
    }

    @Override
    public boolean isMovementCheck() {
        return true;
    }

    @Override
    public void runAsync(NyxPlayerData data) {
        if (!canRun(data)) return;
        handle(data);
    }

    private void applyFullFallDamage(Player player, double fallDistance) {
        if (fallDistance <= 0) return;
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            if (!player.isOnline()) return;
            if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return;

            double damage = Math.max(0, fallDistance - 3.0);
            if (damage <= 0) return;

            for (PotionEffect effect : player.getActivePotionEffects()) {
                if (effect.getType().equals(PotionEffectType.JUMP_BOOST)) {
                    damage = Math.max(0, damage - (effect.getAmplifier() + 1));
                }
            }

            int epf = 0;
            Enchantment ff = Enchantment.getByKey(NamespacedKey.minecraft("feather_falling"));
            Enchantment prot = Enchantment.getByKey(NamespacedKey.minecraft("protection"));
            for (ItemStack armor : player.getInventory().getArmorContents()) {
                if (armor == null) continue;
                if (ff != null) epf += armor.getEnchantmentLevel(ff) * 3;
                if (prot != null) epf += armor.getEnchantmentLevel(prot);
            }
            int effectiveEPF = Math.min(epf, 20);
            damage *= (1.0 - effectiveEPF / 25.0);

            for (PotionEffect effect : player.getActivePotionEffects()) {
                if (effect.getType().equals(PotionEffectType.RESISTANCE)) {
                    damage *= (1.0 - (effect.getAmplifier() + 1) * 0.2);
                }
            }

            int finalDamage = (int) Math.floor(damage);
            if (finalDamage > 0) {
                player.setFallDistance((float) fallDistance);
                player.damage(finalDamage);
            }
        });
    }

    @Override
    public void handle(NyxPlayerData data) {
        Player player = data.getPlayer();
        if (player.isFlying()) return;
        if (data.isInVehicle()) return;

        ItemStack chest = player.getInventory().getChestplate();
        boolean hasElytra = chest != null && chest.getType() == Material.ELYTRA;
        if (hasElytra && data.isGliding()) {
            data.resetAccumulatedPacketFall();
            data.setWurstPatternDetected(false);
            return;
        }
        if (data.isWasGliding() && !data.isGliding()) return;

        boolean onGround = data.isPacketRawOnGround();
        double packetY = data.getPacketRawY();
        boolean isPositionPacket = data.isWasPositionPacket();

        if (data.isInWater() || data.isInLava() || data.isClimbing() || data.isInWeb()) {
            data.resetAccumulatedPacketFall();
            data.setWurstPatternDetected(false);
            return;
        }

        double totalAccum = data.getAccumulatedPacketFall();

        if (!isPositionPacket) {
            if (onGround && totalAccum > WURST_TRACK_THRESHOLD) {
                data.setWurstPatternDetected(true);
            }
            return;
        }

        if (isMitigatingBlockLanding(player, packetY)) {
            data.resetAccumulatedPacketFall();
            data.setWurstPatternDetected(false);
            return;
        }

        double deltaY = !Double.isNaN(data.getLastPosPacketY())
            ? packetY - data.getLastPosPacketY()
            : 0;

        if (deltaY < MIN_FALL_DELTA && !onGround) {
            return;
        }

        if (onGround && isNearGround(packetY, player)) {
            data.setLastSafeLocation(player.getLocation().clone());

            if (data.isWurstPatternDetected() && totalAccum > 0.0) {
                data.incrementNofallCorrections();
                plugin.getLogger().info(String.format("%s NoFall correction #%d WURST FD:%.2f",
                    player.getName(), data.getNofallCorrections(), totalAccum));
                applyFullFallDamage(player, totalAccum);
            }

            data.resetAccumulatedPacketFall();
            data.setWurstPatternDetected(false);
            return;
        }

        if (deltaY < MIN_FALL_DELTA && onGround) {
            data.incrementNofallCorrections();
            plugin.getLogger().info(String.format("%s NoFall correction #%d BLATANT FD:%.2f",
                player.getName(), data.getNofallCorrections(), totalAccum));
            applyFullFallDamage(player, totalAccum);

            data.resetAccumulatedPacketFall();
            data.setWurstPatternDetected(false);
            return;
        }

        if (onGround && !isNearGround(packetY, player)) {
            data.incrementNofallCorrections();
            plugin.getLogger().info(String.format("%s NoFall correction #%d SPOOF FD:%.2f",
                player.getName(), data.getNofallCorrections(), totalAccum));
            applyFullFallDamage(player, totalAccum);
        }
    }

    private boolean isMitigatingBlockLanding(Player player, double y) {
        World world = player.getWorld();
        int yBlock = (int) Math.floor(y - 0.001);
        int px = player.getLocation().getBlockX();
        int pz = player.getLocation().getBlockZ();

        for (int bx = px - 1; bx <= px + 1; bx++) {
            for (int bz = pz - 1; bz <= pz + 1; bz++) {
                Material type = world.getBlockAt(bx, yBlock, bz).getType();
                if (type == Material.SLIME_BLOCK) return true;
                if (type == Material.SWEET_BERRY_BUSH) return true;
                if (type == Material.HAY_BLOCK) return true;
                if (type == Material.HONEY_BLOCK) return true;
                if (type.name().endsWith("_BED")) return true;
                if (type == Material.SCAFFOLDING && player.isSneaking()) return true;
            }
        }
        return false;
    }

    private boolean isNearGround(double y, Player player) {
        var loc = player.getLocation();
        double x = loc.getX();
        double z = loc.getZ();
        World world = player.getWorld();

        BoundingBox feetBox = new BoundingBox(
            x - 0.3, y - 0.001, z - 0.3,
            x + 0.3, y + 0.001, z + 0.3
        );
        feetBox = feetBox.expand(GROUND_BOX_EXPAND, GROUND_BOX_EXPAND, GROUND_BOX_EXPAND);

        int minX = (int) Math.floor(feetBox.minX());
        int minY = (int) Math.floor(feetBox.minY());
        int minZ = (int) Math.floor(feetBox.minZ());
        int maxX = (int) Math.floor(feetBox.maxX());
        int maxY = (int) Math.floor(feetBox.maxY());
        int maxZ = (int) Math.floor(feetBox.maxZ());

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Block block = world.getBlockAt(bx, by, bz);
                    if (block.getType().isCollidable()) {
                        BoundingBox blockBox = new BoundingBox(
                            bx, by, bz, bx + 1, by + 1, bz + 1
                        );
                        if (feetBox.intersects(blockBox)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }
}
