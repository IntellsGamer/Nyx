package dev.idebugger.nyx.punishment;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Date;

public final class PunishmentManager {

    private final Nyx plugin;
    private final MiniMessage miniMessage;
    private final LegacyComponentSerializer legacy;

    public PunishmentManager(Nyx plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.legacy = LegacyComponentSerializer.legacyAmpersand();
    }

    public void execute(String action, Player player, Check check, int vl) {
        if (player == null || !player.isOnline()) return;

        switch (action.toLowerCase()) {
            case "alert" -> sendAlert(player, check, vl);
            case "setback" -> setback(player);
            case "kick" -> kick(player, check);
            case "ban" -> {
                var banManager = plugin.getBanManager();
                if (banManager != null) {
                    banManager.ban(player, check, vl);
                } else {
                    ban(player, check);
                }
            }
            default -> {
                if (action.startsWith("cmd:")) {
                    String cmd = action.substring(4)
                        .replace("%player%", player.getName())
                        .replace("%check%", check.getName())
                        .replace("%vl%", String.valueOf(vl));
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                }
            }
        }
    }

    private void sendAlert(Player player, Check check, int vl) {
        plugin.getAlertManager().sendAlert(
            plugin.getPlayerDataManager().getData(player),
            check, vl, ""
        );
    }

    private void setback(Player player) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            if (!player.isOnline()) return;
            var data = plugin.getPlayerDataManager().getData(player);
            if (data == null) return;

            var history = data.getPositionHistory();
            if (history.isEmpty()) return;

            var safeSnapshot = history.stream()
                .filter(s -> s.onGround())
                .findFirst()
                .orElse(null);

            if (safeSnapshot == null) {
                safeSnapshot = history.peekLast();
            }

            if (safeSnapshot != null) {
                Location loc = safeSnapshot.location();
                if (loc.getX() == 0 && loc.getY() == 0 && loc.getZ() == 0) return;
                player.teleportAsync(loc.clone().add(0, 0.5, 0));
                data.setAlerted(false);
            }
        });
    }

    private Component buildPrefixed(String msgKey, String... placeholders) {
        Component prefix = miniMessage.deserialize(plugin.getNyxConfig().getPrefix());
        String body = plugin.getNyxConfig().getMessage(msgKey, placeholders);
        return prefix.append(Component.text(" ")).append(legacy.deserialize(body));
    }

    private void broadcastPunishment(String msgKey, String playerName, String checkName) {
        Component component = buildPrefixed(msgKey, "player", playerName, "check", checkName);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(component);
        }
        String plain = plugin.getNyxConfig().getMessage(msgKey, "player", playerName, "check", checkName)
            .replaceAll("§[0-9a-fk-or]", "");
        Bukkit.getLogger().info("[Nyx] " + plain);
    }

    private void kick(Player player, Check check) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            if (!player.isOnline()) return;
            String kickMsg = plugin.getNyxConfig().getMessage("punishment.kick");
            player.kick(legacy.deserialize(kickMsg));
            broadcastPunishment("punishment.notify-staff", player.getName(), check.getName());
        });
    }

    private void ban(Player player, Check check) {
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            if (!player.isOnline()) return;
            String banMsg = plugin.getNyxConfig().getMessage("punishment.ban");
            player.kick(legacy.deserialize(banMsg));

            // Time-based native Bukkit ban (never permanent). Duration is
            // configurable; defaults to 3 days if misconfigured.
            long durationMs = plugin.getNyxConfig().getBanDurationMs();
            if (durationMs <= 0) {
                durationMs = 3L * 24 * 60 * 60 * 1000;
            }
            String reason = plugin.getNyxConfig().getBanReason()
                .replace("%check%", check.getName());
            Date expiry = new Date(System.currentTimeMillis() + durationMs);
            String source = "Nyx (" + check.getName() + ")";
            Bukkit.getBanList(BanList.Type.NAME).addBan(player.getName(), reason, expiry, source);

            broadcastPunishment("punishment.broadcast", player.getName(), check.getName());
        });
    }
}
