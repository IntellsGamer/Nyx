package dev.idebugger.nyx.storage;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.checks.Check;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Date;
import java.util.UUID;

/**
 * Coordinates time-based anti-cheat bans with the SQLite {@link StorageManager}.
 *
 * Bans are NEVER permanent: a configurable duration (default 3 days) is applied
 * in every case. Two backends are supported:
 *
 *  1. LiteBans — if the LiteBans plugin is present and enabled, its console
 *     command is used (configurable via {@code punishment.ban.litebans-command}).
 *  2. Bukkit native — otherwise Bukkit's own ban list is used, via
 *     {@link BanList#addBan(String, String, Date, String)}, which supports an
 *     expiry and persists across restarts in banned-players.json.
 *
 * Every ban is also mirrored into the SQLite database so it survives restarts
 * and reconnects and can be re-enforced on join.
 */
public final class BanManager {

    private final Nyx plugin;
    private final StorageManager storage;
    private final LegacyComponentSerializer legacy;
    private final boolean liteBansAvailable;

    public BanManager(Nyx plugin, StorageManager storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.legacy = LegacyComponentSerializer.legacySection();
        this.liteBansAvailable = Bukkit.getPluginManager().getPlugin("LiteBans") != null;
        if (liteBansAvailable) {
            plugin.getLogger().info("BanManager: LiteBans detected, using LiteBans for bans.");
        } else {
            plugin.getLogger().info("BanManager: LiteBans not found, using Bukkit native bans.");
        }
    }

    public boolean isLiteBansAvailable() {
        return liteBansAvailable;
    }

    /** Persist and enforce a time-based anti-cheat ban triggered by a check. */
    public void ban(Player player, Check check, int vl) {
        String reason = plugin.getNyxConfig().getBanReason()
            .replace("%check%", check.getName());
        ban(player.getUniqueId(), player.getName(), check.getName(), reason, effectiveDurationMs());
    }

    /** Manual admin ban via command, using the configured default duration. */
    public void ban(Player player, String reason) {
        ban(player.getUniqueId(), player.getName(), "manual", reason, effectiveDurationMs());
    }

    /** Manual admin ban via command with an explicit duration (ms). */
    public void ban(Player player, String source, String reason, long durationMs) {
        ban(player.getUniqueId(), player.getName(), source, reason, durationMs);
    }

    /** Persist a ban in SQLite and enforce it on the target player. */
    private void ban(UUID uuid, String name, String source, String reason, long durationMs) {
        long expires = System.currentTimeMillis() + durationMs;
        BanRecord ban = new BanRecord(uuid, name, source, reason, System.currentTimeMillis(), expires);
        storage.recordBan(ban);

        enforceBan(uuid, name, source, reason, durationMs);
    }

    private long effectiveDurationMs() {
        long ms = plugin.getNyxConfig().getBanDurationMs();
        // Guard against a misconfigured/zero duration: fall back to 3 days.
        if (ms <= 0) {
            return 3L * 24 * 60 * 60 * 1000;
        }
        return ms;
    }

    private void enforceBan(UUID uuid, String name, String check, String reason, long durationMs) {
        long expires = System.currentTimeMillis() + durationMs;

        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                online.kick(legacy.deserialize(plugin.getNyxConfig().getMessage("punishment.ban")));
            }

            Date expiry = new Date(expires);
            if (liteBansAvailable) {
                dispatchLiteBans(name, check, reason, durationMs);
            } else {
                dispatchBukkit(name, reason, check, expiry);
            }
        });
    }

    private void dispatchLiteBans(String name, String check, String reason, long durationMs) {
        String cmd = plugin.getNyxConfig().getBanCommand()
            .replace("%player%", name)
            .replace("%check%", check)
            .replace("%reason%", reason)
            .replace("%time%", formatDuration(durationMs));
        cmd = cmd.trim();
        if (!cmd.isEmpty() && cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
    }

    private void dispatchBukkit(String name, String reason, String check, Date expiry) {
        String source = "Nyx (" + check + ")";
        BanList banList = Bukkit.getBanList(BanList.Type.NAME);
        // Bukkit has no rich native ban interface (unlike LiteBans), so we make
        // the kick/reason screen more informative with color + expiry info.
        String richReason = "§c§l[Nyx] §f§lUnfair Advantage\n"
            + "§7Check: §f" + check + "\n"
            + "§7You have been banned for using unfair modifications\n"
            + "§7Banned by: §fNyx\n"
            + "§7Expires: §f" + expiry;
        // If an existing (possibly already-expired) entry is present, silently
        // update it with the new expiry instead of raising a duplicate-ban error.
        boolean alreadyBanned = banList.isBanned(name);
        banList.addBan(name, richReason, expiry, source);
        if (!alreadyBanned) {
            plugin.getLogger().info("[Nyx] Banned " + name + " for " + check + " until " + expiry);
        }
    }

    /** Called on join: re-apply any persistent ban that has not yet expired. */
    public void applyPersistentBan(Player player) {
        if (player == null || !player.isOnline()) return;
        BanRecord ban = storage.getActiveBan(player.getUniqueId());
        if (ban == null) return;

        UUID uuid = player.getUniqueId();
        long remaining = ban.expiresAt() - System.currentTimeMillis();
        if (remaining <= 0) {
            // Record is stale; drop it rather than re-banning.
            storage.clearBan(uuid);
            return;
        }

        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            if (!player.isOnline()) return;
            player.kick(legacy.deserialize(plugin.getNyxConfig().getMessage("punishment.ban")));
            Date expiry = new Date(ban.expiresAt());
            if (liteBansAvailable) {
                dispatchLiteBans(player.getName(), ban.check(), ban.reason(), remaining);
            } else {
                dispatchBukkit(player.getName(), ban.reason(), ban.check(), expiry);
            }
        });
    }

    /** Formats a millisecond duration back to a compact human/command string. */
    public static String formatDuration(long ms) {
        long seconds = Math.max(1, ms / 1000L);
        if (seconds % 604800L == 0) return (seconds / 604800L) + "w";
        if (seconds % 86400L == 0) return (seconds / 86400L) + "d";
        if (seconds % 3600L == 0) return (seconds / 3600L) + "h";
        if (seconds % 60L == 0) return (seconds / 60L) + "m";
        return seconds + "s";
    }

    /**
     * Unbans a player (by name), removing every trace Nyx keeps:
     * <ol>
     *   <li>the persistent SQLite ban record — otherwise {@link #applyPersistentBan}
     *       would re-ban them the moment they rejoin (this is why plain
     *       {@code /pardon} alone does not work);</li>
     *   <li>the accumulated setback/kick escalation counts;</li>
     *   <li>any native Bukkit ban-list entry; and</li>
     *   <li>the LiteBans ban (via the configured console command).</li>
     * </ol>
     *
     * @return true if any record/entry actually existed and was removed.
     */
    public boolean unban(String name) {
        if (name == null || name.isBlank()) return false;
        boolean removed = false;

        BanRecord ban = storage.getBanByName(name);
        if (ban != null) {
            UUID uuid = ban.playerUuid();
            storage.clearBan(uuid);
            storage.clearEscalation(uuid);
            removed = true;
        }

        if (Bukkit.getBanList(BanList.Type.NAME).isBanned(name)) {
            Bukkit.getBanList(BanList.Type.NAME).pardon(name);
            removed = true;
        }

        if (liteBansAvailable) {
            String cmd = plugin.getNyxConfig().getUnbanCommand()
                .replace("%player%", name);
            cmd = cmd.trim();
            if (!cmd.isEmpty() && cmd.startsWith("/")) {
                cmd = cmd.substring(1);
            }
            if (!cmd.isEmpty()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                removed = true;
            }
        }

        if (removed) {
            plugin.getLogger().info("[Nyx] Unbanned " + name + ".");
        }
        return removed;
    }

    /** Names of all currently-banned players, for tab completion. */
    public java.util.List<String> getBannedNames() {
        return storage.getBannedNames();
    }
}
