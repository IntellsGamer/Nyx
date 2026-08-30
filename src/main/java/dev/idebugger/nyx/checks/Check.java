package dev.idebugger.nyx.checks;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.NyxConfig;
import dev.idebugger.nyx.data.NyxPlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public abstract class Check {

    protected final Nyx plugin;
    private final String name;
    private final String configKey;

    protected Check(Nyx plugin) {
        this.plugin = plugin;
        CheckData data = getClass().getAnnotation(CheckData.class);
        this.name = data != null ? data.name() : getClass().getSimpleName();
        this.configKey = this.name.toLowerCase();
    }

    public abstract void handle(NyxPlayerData data);
    public abstract boolean isMovementCheck();

    public String getName() {
        return name;
    }

    public String getConfigKey() {
        return configKey;
    }

    public boolean isEnabled() {
        return plugin.getNyxConfig().isCheckEnabled(configKey);
    }

    public NyxConfig.CheckConfig getConfig() {
        return plugin.getNyxConfig().getCheckConfig(configKey);
    }

    public boolean canRun(NyxPlayerData data) {
        if (data == null || data.getPlayer() == null) return false;
        Player player = data.getPlayer();

        if (!player.isOnline()) return false;

        if (!isEnabled() || getConfig() == null) return false;

        if (data.isExempt()) return false;

        if (player.hasPermission("nyx.bypass.*") || player.hasPermission("nyx.bypass." + configKey)) {
            return false;
        }

        if (plugin.getNyxConfig().getExemptWorlds().contains(player.getWorld().getName())) {
            return false;
        }

        GameMode gm = player.getGameMode();
        if (plugin.getNyxConfig().getExemptGamemodes().contains(gm.name().toLowerCase())) {
            return false;
        }

        if (data.getTicksSinceJoin() < 20) return false;

        return true;
    }

    public void flag(NyxPlayerData data, double extraInfo) {
        flag(data, String.format("%.2f", extraInfo));
    }

    public void flag(NyxPlayerData data, String info) {
        if (data == null) return;

        data.addViolation(configKey);
        int vl = data.getViolations(configKey);

        NyxConfig.CheckConfig config = getConfig();
        if (config == null) return;

        int maxVl = config.maxViolations();

        if (vl > 2 && (!data.isAlerted() || (config.threshold() > 0 && vl % config.threshold() == 0))) {
            plugin.getAlertManager().sendAlert(data, this, vl, info);
            data.setAlerted(true);
        }

        List<String> actions = config.actions();
        for (String action : actions) {
            String[] parts = action.split(" @ ");
            String actionType = parts[0].trim().toLowerCase();
            int atVl = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : Integer.MAX_VALUE;

            if (vl == atVl) {
                plugin.getPunishmentManager().execute(actionType, data.getPlayer(), this, vl);
            }
        }
    }

    public void runAsync(NyxPlayerData data) {
        if (!canRun(data)) return;

        if (plugin.getNyxConfig().isAsyncChecks()) {
            CompletableFuture.runAsync(() -> handle(data), plugin.getExecutorService());
        } else {
            handle(data);
        }
    }
}
