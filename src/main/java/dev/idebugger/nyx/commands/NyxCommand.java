package dev.idebugger.nyx.commands;

import dev.idebugger.nyx.Nyx;
import dev.idebugger.nyx.data.NyxPlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

public final class NyxCommand implements CommandExecutor, TabCompleter {

    private final Nyx plugin;
    private final MiniMessage miniMessage;
    private final LegacyComponentSerializer legacy;

    public NyxCommand(Nyx plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.legacy = LegacyComponentSerializer.legacySection();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "alerts" -> handleAlerts(sender);
            case "check" -> handleCheck(sender, args);
            case "reload" -> handleReload(sender);
            case "setback" -> handleSetback(sender, args);
            case "exempt" -> handleExempt(sender, args);
            case "gui" -> handleGUI(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(miniMessage.deserialize(
            "<gradient:#ff6b6b:#ffd93d>=== Nyx Anticheat ===</gradient>"
        ));
        sender.sendMessage(Component.text("/nyx alerts", NamedTextColor.GOLD)
            .append(Component.text(" — Toggle alerts", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/nyx check <player>", NamedTextColor.GOLD)
            .append(Component.text(" — Show violations", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/nyx reload", NamedTextColor.GOLD)
            .append(Component.text(" — Reload config", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/nyx setback <player>", NamedTextColor.GOLD)
            .append(Component.text(" — Setback a player", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/nyx exempt <player>", NamedTextColor.GOLD)
            .append(Component.text(" — Toggle exemption", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/nyx gui", NamedTextColor.GOLD)
            .append(Component.text(" — Open checks GUI", NamedTextColor.GRAY)));
    }

    private void handleAlerts(CommandSender sender) {
        if (!sender.hasPermission("nyx.alerts")) {
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.no-permission")));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.player-only")));
            return;
        }

        NyxPlayerData data = plugin.getPlayerDataManager().getData(player);
        data.setAlerted(!data.isAlerted());

        String msg = data.isAlerted()
            ? plugin.getNyxConfig().getMessage("commands.alerts.toggled-on")
            : plugin.getNyxConfig().getMessage("commands.alerts.toggled-off");
        sender.sendMessage(legacy.deserialize(msg));
    }

    private void handleCheck(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nyx.admin")) {
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.no-permission")));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /nyx check <player>", NamedTextColor.RED));
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.invalid-player")));
            return;
        }

        NyxPlayerData data = plugin.getPlayerDataManager().getData(target);
        if (data == null) {
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.check.no-data")));
            return;
        }

        var violations = data.getViolationMap();
        if (violations.isEmpty()) {
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.check.no-violations")));
            return;
        }

        String header = plugin.getNyxConfig().getMessage("commands.check.header", "player", target.getName());
        sender.sendMessage(legacy.deserialize(header));

        violations.forEach((check, vl) -> {
            sender.sendMessage(Component.text("  " + check, NamedTextColor.YELLOW)
                .append(Component.text(": " + vl + " VL", NamedTextColor.RED)));
        });
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("nyx.reload")) {
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.no-permission")));
            return;
        }

        try {
            plugin.getNyxConfig().reload();
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.reload.success")));
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to reload config: " + e.getMessage());
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.reload.fail")));
        }
    }

    private void handleSetback(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nyx.setback")) {
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.no-permission")));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /nyx setback <player>", NamedTextColor.RED));
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.invalid-player")));
            return;
        }

        plugin.getPunishmentManager().execute("setback", target, null, 0);
        String msg = plugin.getNyxConfig().getMessage("commands.setback.success", "player", target.getName());
        sender.sendMessage(legacy.deserialize(msg));
    }

    private void handleExempt(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nyx.exempt")) {
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.no-permission")));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /nyx exempt <player>", NamedTextColor.RED));
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.invalid-player")));
            return;
        }

        NyxPlayerData data = plugin.getPlayerDataManager().getData(target);
        if (data == null) {
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.check.no-data")));
            return;
        }

        boolean newExempt = !data.isExempt();
        data.setExempt(newExempt);

        String msgKey = newExempt ? "commands.exempt.added" : "commands.exempt.removed";
        String msg = plugin.getNyxConfig().getMessage(msgKey, "player", target.getName());
        sender.sendMessage(legacy.deserialize(msg));
    }

    private void handleGUI(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.player-only")));
            return;
        }

        if (!sender.hasPermission("nyx.admin")) {
            sender.sendMessage(legacy.deserialize(plugin.getNyxConfig().getMessage("commands.no-permission")));
            return;
        }

        plugin.getChecksGUI().open(player);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("alerts", "check", "reload", "setback", "exempt", "gui").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("check")
            || args[0].equalsIgnoreCase("setback")
            || args[0].equalsIgnoreCase("exempt"))) {
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                .collect(Collectors.toList());
        }

        return List.of();
    }
}
