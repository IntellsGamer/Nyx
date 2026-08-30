package dev.idebugger.nyx.alert;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import dev.idebugger.nyx.data.NyxPlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AlertManager {

    private final Nyx plugin;
    private final MiniMessage miniMessage;
    private final LegacyComponentSerializer legacy;
    private final Map<UUID, Long> lastAlertTimes;

    public AlertManager(Nyx plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.legacy = LegacyComponentSerializer.legacySection();
        this.lastAlertTimes = new ConcurrentHashMap<>();
    }

    public void sendAlert(NyxPlayerData data, Check check, int vl, String info) {
        if (data == null || data.getPlayer() == null) return;
        Player player = data.getPlayer();

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long lastAlert = lastAlertTimes.get(uuid);
        long cooldown = plugin.getNyxConfig().getAlertCooldown();

        if (lastAlert != null && (now - lastAlert) < cooldown) return;
        lastAlertTimes.put(uuid, now);

        String messageKey = plugin.getNyxConfig().isVerboseAlerts() && !info.isEmpty()
            ? "alert.verbose" : "alert.staff";
        String message = plugin.getNyxConfig().getMessage(messageKey,
            "player", player.getName(),
            "check", check.getName(),
            "violations", String.valueOf(vl),
            "info", info
        );

        Component prefixComponent = miniMessage.deserialize(plugin.getNyxConfig().getPrefix());
        Component bodyComponent = legacy.deserialize(message);
        Component component = prefixComponent.append(Component.text(" ")).append(bodyComponent);
        String adminPerm = plugin.getNyxConfig().getAdminPermission();

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(adminPerm)) {
                staff.sendMessage(component);
            }
        }

        String plainInfo = info.isEmpty() ? "" : " [" + info + "]";
        Bukkit.getLogger().info("[Nyx] " + player.getName() + " failed "
            + check.getName() + " (VL: " + vl + ")" + plainInfo);

        if (plugin.getNyxConfig().isDiscordEnabled()) {
            plugin.getDiscordWebhook().sendAlert(player.getName(), check.getName(), vl, info);
        }
    }
}
