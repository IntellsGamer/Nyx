package dev.nyx.gui;

import dev.nyx.Nyx;
import dev.nyx.NyxConfig.CheckConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public final class ChecksGUI implements Listener {

    private final Nyx plugin;
    private final LegacyComponentSerializer legacy;
    private final Map<UUID, Inventory> openInventories;
    private final Map<UUID, Integer> playerPages;
    private static final int CHECKS_PER_PAGE = 18;

    private static final String[] ALL_CHECKS = {
        "speed", "fly", "nofall", "timer", "phase", "jesus",
        "boatfly",
        "elytraa", "elytrab", "elytrac", "extraelytra",
        "killaura", "reach", "aimassist", "autoclicker", "velocity",
        "hitbox", "selfinteract", "noswing", "multiinteract",
        "attackwhileusing", "aimmodulo360",
        "tridenta", "tridentb",
        "badpackets", "inventorymove", "fastuse",
        "fastbreak", "fastplace",
        "web",
        "boat", "entityspeed", "entitycontrol"
    };

    private static final Map<String, Material> CHECK_MATERIALS = new LinkedHashMap<>();

    static {
        CHECK_MATERIALS.put("speed", Material.FEATHER);
        CHECK_MATERIALS.put("fly", Material.ELYTRA);
        CHECK_MATERIALS.put("nofall", Material.SLIME_BLOCK);
        CHECK_MATERIALS.put("timer", Material.CLOCK);
        CHECK_MATERIALS.put("phase", Material.BARRIER);
        CHECK_MATERIALS.put("jesus", Material.WATER_BUCKET);
        CHECK_MATERIALS.put("boatfly", Material.OAK_BOAT);
        CHECK_MATERIALS.put("elytraa", Material.ELYTRA);
        CHECK_MATERIALS.put("elytrab", Material.ELYTRA);
        CHECK_MATERIALS.put("elytrac", Material.ELYTRA);
        CHECK_MATERIALS.put("extraelytra", Material.ELYTRA);
        CHECK_MATERIALS.put("killaura", Material.DIAMOND_SWORD);
        CHECK_MATERIALS.put("reach", Material.BOW);
        CHECK_MATERIALS.put("aimassist", Material.TARGET);
        CHECK_MATERIALS.put("autoclicker", Material.STONE_BUTTON);
        CHECK_MATERIALS.put("velocity", Material.SHIELD);
        CHECK_MATERIALS.put("hitbox", Material.ENDER_PEARL);
        CHECK_MATERIALS.put("selfinteract", Material.PLAYER_HEAD);
        CHECK_MATERIALS.put("noswing", Material.STICK);
        CHECK_MATERIALS.put("multiinteract", Material.NETHERITE_SWORD);
        CHECK_MATERIALS.put("attackwhileusing", Material.SHIELD);
        CHECK_MATERIALS.put("aimmodulo360", Material.COMPASS);
        CHECK_MATERIALS.put("tridenta", Material.TRIDENT);
        CHECK_MATERIALS.put("tridentb", Material.TRIDENT);
        CHECK_MATERIALS.put("badpackets", Material.REDSTONE);
        CHECK_MATERIALS.put("inventorymove", Material.CHEST);
        CHECK_MATERIALS.put("fastuse", Material.GOLDEN_APPLE);
        CHECK_MATERIALS.put("fastbreak", Material.NETHERITE_PICKAXE);
        CHECK_MATERIALS.put("fastplace", Material.BRICK);
        CHECK_MATERIALS.put("web", Material.COBWEB);
        CHECK_MATERIALS.put("boat", Material.OAK_BOAT);
        CHECK_MATERIALS.put("entityspeed", Material.MINECART);
        CHECK_MATERIALS.put("entitycontrol", Material.SADDLE);
    }

    public ChecksGUI(Nyx plugin) {
        this.plugin = plugin;
        this.legacy = LegacyComponentSerializer.legacySection();
        this.openInventories = new HashMap<>();
        this.playerPages = new HashMap<>();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        playerPages.put(player.getUniqueId(), 0);
        openPage(player, 0);
    }

    private void openPage(Player player, int page) {
        int totalPages = (int) Math.ceil((double) ALL_CHECKS.length / CHECKS_PER_PAGE);
        int target = Math.max(0, Math.min(page, totalPages - 1));
        playerPages.put(player.getUniqueId(), target);

        Inventory inv = Bukkit.createInventory(null, 27,
            Component.text("Nyx Anticheat - Page " + (target + 1) + "/" + totalPages));

        int start = target * CHECKS_PER_PAGE;
        int end = Math.min(start + CHECKS_PER_PAGE, ALL_CHECKS.length);
        int slot = 0;

        for (int i = start; i < end; i++) {
            String checkName = ALL_CHECKS[i];
            CheckConfig config = plugin.getNyxConfig().getCheckConfig(checkName);
            boolean enabled = config != null && config.enabled();

            ItemStack item = new ItemStack(enabled ? Material.LIME_DYE : Material.GRAY_DYE);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(legacy.deserialize(
                    (enabled ? "§a" : "§c") + capitalize(checkName)));
                List<Component> lore = new ArrayList<>();
                lore.add(legacy.deserialize("§7Status: " + (enabled ? "§aEnabled" : "§cDisabled")));
                if (config != null) {
                    lore.add(legacy.deserialize("§7Threshold: " + config.threshold()));
                    lore.add(legacy.deserialize("§7Max VL: " + config.maxViolations()));
                    lore.add(legacy.deserialize("§7Sensitivity: " + String.format("%.1f", config.sensitivity())));
                }
                lore.add(Component.text(""));
                lore.add(legacy.deserialize(enabled ? "§cClick to disable" : "§aClick to enable"));
                meta.lore(lore);
                item.setItemMeta(meta);
            }

            inv.setItem(slot, item);
            slot++;
        }

        if (target > 0) {
            ItemStack prevArrow = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prevArrow.getItemMeta();
            if (prevMeta != null) {
                prevMeta.displayName(legacy.deserialize("§aPrevious Page"));
                prevArrow.setItemMeta(prevMeta);
            }
            inv.setItem(21, prevArrow);
        }

        ItemStack pageInfo = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = pageInfo.getItemMeta();
        if (infoMeta != null) {
            infoMeta.displayName(legacy.deserialize("§ePage " + (target + 1) + "/" + totalPages));
            pageInfo.setItemMeta(infoMeta);
        }
        inv.setItem(22, pageInfo);

        if (target < totalPages - 1) {
            ItemStack nextArrow = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = nextArrow.getItemMeta();
            if (nextMeta != null) {
                nextMeta.displayName(legacy.deserialize("§aNext Page"));
                nextArrow.setItemMeta(nextMeta);
            }
            inv.setItem(23, nextArrow);
        }

        player.openInventory(inv);
        openInventories.put(player.getUniqueId(), inv);
        playerPages.put(player.getUniqueId(), target);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!openInventories.containsKey(player.getUniqueId())) return;
        if (!event.getInventory().equals(openInventories.get(player.getUniqueId()))) return;

        event.setCancelled(true);

        int slot = event.getSlot();
        int page = playerPages.getOrDefault(player.getUniqueId(), 0);
        int totalPages = (int) Math.ceil((double) ALL_CHECKS.length / CHECKS_PER_PAGE);

        if (slot == 21 && page > 0) {
            openPage(player, page - 1);
            return;
        }
        if (slot == 23 && page < totalPages - 1) {
            openPage(player, page + 1);
            return;
        }

        int checkIndex = page * CHECKS_PER_PAGE + slot;
        if (slot >= CHECKS_PER_PAGE || checkIndex >= ALL_CHECKS.length) return;

        String checkName = ALL_CHECKS[checkIndex];
        toggleCheck(checkName);
        openPage(player, page);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            openInventories.remove(player.getUniqueId());
            playerPages.remove(player.getUniqueId());
        }
    }

    private void toggleCheck(String checkName) {
        var config = plugin.getConfig();
        String path = "checks." + checkName + ".enabled";
        boolean current = config.getBoolean(path, true);
        config.set(path, !current);
        plugin.saveConfig();
        plugin.getNyxConfig().reload();
    }

    private String capitalize(String str) {
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        openInventories.clear();
        playerPages.clear();
    }
}
