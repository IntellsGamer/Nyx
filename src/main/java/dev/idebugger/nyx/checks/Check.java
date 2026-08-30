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

        boolean autobanActive = plugin.getNyxConfig().isAutobanEnabled() && config.autoban();
        List<String> actions = config.actions();

        // Whether any kick/ban punishment is configured. When it is, setbacks act
        // as one-shot tripwires (fire once when the player first crosses their
        // threshold); the kick/ban above handles continued punishment. When the
        // highest configured punishment is a setback (no kick/ban), the setback
        // repeats on a time interval instead of on every single VL.
        boolean hasKickOrBan = false;
        for (String action : actions) {
            String type = action.split(" @ ")[0].trim().toLowerCase();
            if (type.equals("kick") || type.equals("ban")) {
                hasKickOrBan = true;
                break;
            }
        }

        for (String action : actions) {
            String[] parts = action.split(" @ ");
            String actionType = parts[0].trim().toLowerCase();
            int atVl = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : Integer.MAX_VALUE;

            if (actionType.equals("setback")) {
                handleSetback(data, atVl, vl, hasKickOrBan, config, autobanActive);
                continue;
            }

            // Kicks and bans keep firing whenever the violation level is AT or
            // ABOVE the threshold, not just on some exact/once crossing. A
            // sustained cheat keeps re-triggering them (each incrementing its
            // escalation count), so the kicks-to-ban floors are reached
            // deterministically.
            if (vl >= atVl) {
                enforce(data, actionType, vl, config, autobanActive);
            }
        }
    }

    /**
     * Applies a setback punishment with the desired timing:
     *  - when a kick/ban is configured above it, setback fires ONCE the first time
     *    the player crosses this threshold (VL goes 8 -> 12 while setback @ 10,
     *    so a setback is issued at that crossing) and then not again on every VL;
     *  - when the setback is the highest punishment configured (no kick/ban), it
     *    repeats no faster than the per-check setback interval instead of firing
     *    on every single VL.
     */
    private void handleSetback(NyxPlayerData data, int atVl, int vl, boolean hasKickOrBan,
                               NyxConfig.CheckConfig config, boolean autobanActive) {
        if (vl < atVl) {
            // VL fell back below this point: re-arm so a future genuine crossing
            // of the threshold fires the setback again.
            int fired = data.getSetbackFired(configKey);
            if (fired >= atVl && fired != Integer.MAX_VALUE) {
                data.setSetbackFired(configKey, atVl - 1);
            }
            return;
        }

        if (hasKickOrBan) {
            // One-shot: only when crossing this threshold point for the first time.
            int fired = data.getSetbackFired(configKey);
            if (fired >= atVl) return;
            data.setSetbackFired(configKey, atVl);
            enforce(data, "setback", vl, config, autobanActive);
        } else {
            // Setback is the highest punishment: repeat on a cooldown, not per VL.
            long now = System.currentTimeMillis();
            long interval = Math.max(50, config.setbackInterval());
            if (now - data.getLastSetbackTime(configKey) >= interval) {
                data.setLastSetbackTime(configKey, now);
                enforce(data, "setback", vl, config, autobanActive);
            }
        }
    }

    /**
     * Executes a single punishment action, applying repeated-setback/kick
     * escalation into a time-based ban exactly as before.
     */
    private void enforce(NyxPlayerData data, String actionType, int vl,
                         NyxConfig.CheckConfig config, boolean autobanActive) {
        // Fire whenever the violation level is AT or ABOVE the threshold,
        // not just on some exact/once crossing. A sustained cheat keeps
        // re-triggering the setback/kick (each incrementing its escalation
        // count), so the setbacks-to-ban / kicks-to-ban floors are reached
        // deterministically. Inflation via hovering is impossible because
        // the escalation counts no longer decay.
        int escalateToBan = autobanActive ? switch (actionType) {
            case "setback" -> config.setbacksToBan();
            case "kick" -> config.kicksToBan();
            default -> 0;
        } : 0;
        if (escalateToBan > 0) {
            // Repeated setbacks OR kicks on the SAME check signal someone
            // persistently fighting the anticheat. After the per-check
            // threshold we escalate the punishment into a time-based ban
            // instead of repeating the setback/kick. 0 disables that route.
            int count = actionType.equals("setback")
                ? data.incrementSetbackCount(configKey)
                : data.incrementKickCount(configKey);
            persistEscalationCounts(data);
            if (count >= escalateToBan) {
                // A ban resets both floors so a released offender starts
                // clean; escalation restarts fresh on the next offence.
                data.resetSetbackCount(configKey);
                data.resetKickCount(configKey);
                persistEscalationCounts(data);
                plugin.getPunishmentManager().execute("ban", data.getPlayer(), this, vl);
            } else {
                plugin.getPunishmentManager().execute(actionType, data.getPlayer(), this, vl);
            }
        } else if (!autobanActive && actionType.equals("ban")) {
            // Auto-ban disabled (globally or for this check): skip the
            // high-VL auto-ban action entirely. Manual /nyx ban is unaffected.
        } else {
            plugin.getPunishmentManager().execute(actionType, data.getPlayer(), this, vl);
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

    /**
     * Mirrors this player/check's setback+kick escalation counts into SQLite so
     * they survive a rejoin. Without this a kicked player reconnects with a fresh
     * session (counts back at zero) and the kicks-to-ban floor is never reached.
     */
    private void persistEscalationCounts(NyxPlayerData data) {
        var storage = plugin.getStorageManager();
        if (storage == null) return;
        storage.setEscalation(data.getUuid(), configKey,
            data.getSetbackCount(configKey), data.getKickCount(configKey));
    }
}
