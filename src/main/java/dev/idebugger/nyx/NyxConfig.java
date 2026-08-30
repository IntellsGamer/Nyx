package dev.idebugger.nyx;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

public final class NyxConfig {

    private final Nyx plugin;
    private FileConfiguration config;
    private FileConfiguration messages;
    private File messagesFile;

    private String prefix;
    private boolean alertsEnabled;
    private long alertCooldown;
    private boolean verboseAlerts;
    private boolean asyncChecks;
    private boolean virtualThreads;
    private Set<String> exemptGamemodes;
    private List<String> exemptWorlds;
    private boolean discordEnabled;
    private String discordWebhookUrl;
    private String adminPermission;
    private String banCommand;
    private String banDuration;
    private String banReason;
    private String unbanCommand;
    private boolean autobanEnabled;
    private boolean persistGlobally;

    private final Map<String, CheckConfig> checkConfigs = new LinkedHashMap<>();

    public NyxConfig(Nyx plugin) {
        this.plugin = plugin;
        setupMessagesFile();
        reload();
    }

    private void setupMessagesFile() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
    }

    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        if (messagesFile != null && messagesFile.exists()) {
            this.messages = YamlConfiguration.loadConfiguration(messagesFile);
            mergeDefaultMessages();
        } else {
            this.messages = new YamlConfiguration();
        }

        this.prefix = config.getString("general.prefix", "<gray>[<gradient:#ff6b6b:#ffd93d>Nyx</gradient>]</gray>");
        this.alertsEnabled = config.getBoolean("alerts.enabled", true);
        this.alertCooldown = config.getLong("alerts.cooldown", 1000);
        this.verboseAlerts = config.getBoolean("alerts.verbose", false);
        this.asyncChecks = config.getBoolean("performance.async-checks", true);
        this.virtualThreads = config.getBoolean("performance.virtual-threads", true);
        this.exemptGamemodes = new HashSet<>(config.getStringList("bypass.gamemodes"));
        this.exemptWorlds = config.getStringList("bypass.worlds");
        this.discordEnabled = config.getBoolean("discord.enabled", false);
        this.discordWebhookUrl = config.getString("discord.webhook-url", "");
        this.adminPermission = config.getString("alerts.permission", "nyx.admin");
        this.banCommand = config.getString("punishment.ban.litebans-command", "litebans:ban %player% %time% %reason%");
        this.banDuration = config.getString("punishment.ban.duration", "3d");
        this.banReason = config.getString("punishment.ban.reason", "§c[Nyx] §fUnfair Advantage §7(%check%)");
        this.unbanCommand = config.getString("punishment.ban.litebans-unban-command", "litebans:unban %player%");
        this.autobanEnabled = config.getBoolean("punishment.ban.autoban-enabled", true);
        this.persistGlobally = config.getBoolean("storage.persist-global-violations", true);

        loadCheckConfigs();
        mergeDefaultConfig();
    }

    private void mergeDefaultConfig() {
        try {
            var reader = new java.io.InputStreamReader(plugin.getResource("config.yml"), java.nio.charset.StandardCharsets.UTF_8);
            FileConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (!config.contains(key)) {
                    config.set(key, defaults.get(key));
                    changed = true;
                }
            }
            if (changed) {
                config.save(new File(plugin.getDataFolder(), "config.yml"));
                loadCheckConfigs();
            }
        } catch (Exception ignored) {}
    }

    private void mergeDefaultMessages() {
        try {
            var reader = new java.io.InputStreamReader(plugin.getResource("messages.yml"), java.nio.charset.StandardCharsets.UTF_8);
            FileConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
            for (String key : defaults.getKeys(true)) {
                if (!messages.contains(key)) {
                    messages.set(key, defaults.get(key));
                }
            }
            messages.save(messagesFile);
        } catch (Exception ignored) {}
    }

    private static final Map<String, List<String>> DEFAULT_ACTIONS = Map.ofEntries(
        Map.entry("speed", List.of("alert", "setback @ 20", "kick @ 50")),
        Map.entry("fly", List.of("alert", "setback @ 20", "kick @ 40")),
        Map.entry("nofall", List.of("alert", "setback @ 10", "kick @ 20")),
        Map.entry("timer", List.of("alert", "kick @ 30")),
        Map.entry("phase", List.of("alert", "setback @ 3", "kick @ 10")),
        Map.entry("jesus", List.of("alert", "setback @ 12", "kick @ 20")),
        Map.entry("boatfly", List.of("alert", "setback @ 10", "kick @ 20")),
        Map.entry("killaura", List.of("alert", "kick @ 30")),
        Map.entry("reach", List.of("alert", "setback @ 8", "kick @ 15")),
        Map.entry("aimassist", List.of("alert", "kick @ 40")),
        Map.entry("autoclicker", List.of("alert", "kick @ 30")),
        Map.entry("velocity", List.of("alert", "setback @ 15", "kick @ 25")),
        Map.entry("hitbox", List.of("alert", "kick @ 15")),
        Map.entry("selfinteract", List.of("alert", "kick @ 3")),
        Map.entry("multiinteract", List.of("alert", "kick @ 10")),
        Map.entry("noswing", List.of("alert", "kick @ 20")),
        Map.entry("aimmodulo360", List.of("alert", "kick @ 15")),
        Map.entry("attackwhileusing", List.of("alert", "kick @ 10")),
        Map.entry("entityspeed", List.of("alert", "kick @ 15")),
        Map.entry("entitycontrol", List.of("alert", "kick @ 15")),
        Map.entry("boat", List.of("alert", "kick @ 15")),
        Map.entry("elytraa", List.of("alert", "kick @ 10")),
        Map.entry("elytrab", List.of("alert", "kick @ 10")),
        Map.entry("elytrac", List.of("alert", "kick @ 10")),
        Map.entry("extraelytra", List.of("alert", "setback @ 3", "setback @ 5", "kick @ 15")),
        Map.entry("tridenta", List.of("alert", "kick @ 10")),
        Map.entry("tridentb", List.of("alert", "kick @ 10")),
        Map.entry("badpackets", List.of("alert", "kick @ 15")),
        Map.entry("inventorymove", List.of("alert", "setback @ 15", "kick @ 20")),
        Map.entry("fastbreak", List.of("alert", "kick @ 20")),
        Map.entry("fastplace", List.of("alert", "kick @ 25")),
        Map.entry("fastuse", List.of("alert", "kick @ 25")),
        Map.entry("web", List.of("alert", "setback @ 6", "kick @ 10")),
        Map.entry("scaffold", List.of("alert", "kick @ 20"))
    );

    private static final Map<String, Integer> DEFAULT_SETBACKS_TO_BAN = Map.ofEntries(
        // Generous by default — setback spam is the least damning signal and is
        // prone to false positives from lag, so it takes many setbacks to ban.
        Map.entry("phase", 3),
        Map.entry("selfinteract", 3),
        Map.entry("reach", 4),
        Map.entry("fly", 5),
        Map.entry("jesus", 5),
        Map.entry("web", 4),
        Map.entry("extraelytra", 4),
        Map.entry("speed", 6),
        Map.entry("velocity", 5),
        Map.entry("inventorymove", 6),
        Map.entry("nofall", 8),
        Map.entry("boatfly", 8)
    );

    // Less mercy than setbacks: getting kicked already means repeated, high-VL
    // cheating, so far fewer kicks are needed before a ban.
    private static final Map<String, Integer> DEFAULT_KICKS_TO_BAN = Map.ofEntries(
        Map.entry("phase", 2),
        Map.entry("selfinteract", 2),
        Map.entry("reach", 3),
        Map.entry("fly", 3),
        Map.entry("jesus", 3),
        Map.entry("web", 3),
        Map.entry("extraelytra", 2),
        Map.entry("speed", 3),
        Map.entry("velocity", 3),
        Map.entry("inventorymove", 4),
        Map.entry("nofall", 5),
        Map.entry("boatfly", 5),
        Map.entry("timer", 3),
        Map.entry("killaura", 3),
        Map.entry("aimassist", 3),
        Map.entry("autoclicker", 3),
        Map.entry("hitbox", 3),
        Map.entry("multiinteract", 3),
        Map.entry("noswing", 4),
        Map.entry("aimmodulo360", 3),
        Map.entry("attackwhileusing", 3),
        Map.entry("entityspeed", 3)
    );

    private static int defaultSetbacksToBan(String check) {
        return DEFAULT_SETBACKS_TO_BAN.getOrDefault(check, 5);
    }

    private static int defaultKicksToBan(String check) {
        return DEFAULT_KICKS_TO_BAN.getOrDefault(check, 3);
    }

    private void loadCheckConfigs() {
        checkConfigs.clear();
        ConfigurationSection checksSection = config.getConfigurationSection("checks");
        if (checksSection == null) return;

        for (String checkName : checksSection.getKeys(false)) {
            ConfigurationSection section = checksSection.getConfigurationSection(checkName);
            if (section == null) continue;

            List<String> actions = new ArrayList<>(section.getStringList("actions"));
            List<String> defaultActions = DEFAULT_ACTIONS.get(checkName);
            if (defaultActions != null) {
                for (String a : defaultActions) {
                    if (!actions.contains(a)) {
                        actions.add(a);
                    }
                }
            }

            CheckConfig cc = new CheckConfig(
                section.getBoolean("enabled", true),
                section.getBoolean("autoban", true),
                section.getInt("threshold", 10),
                section.getInt("decay", 1),
                section.getInt("max-violations", 30),
                section.getDouble("sensitivity", 0.7),
                actions,
                section.getInt("setbacks-to-ban", defaultSetbacksToBan(checkName)),
                section.getInt("kicks-to-ban", defaultKicksToBan(checkName)),
                section.getLong("setbacks-interval", 2000)
            );
            checkConfigs.put(checkName, cc);
        }
    }

    public String getMessage(String path, String... placeholders) {
        String msg = messages.getString(path);
        if (msg == null) {
            plugin.getLogger().warning("Missing message key in messages.yml: " + path);
            return "§cMissing message: " + path;
        }
        msg = msg.replace("<prefix>", prefix);
        for (int i = 0; i < placeholders.length - 1; i += 2) {
            msg = msg.replace("<" + placeholders[i] + ">", placeholders[i + 1]);
        }
        return msg;
    }

    public String getPrefixFormatted() { return prefix; }
    public boolean isAlertsEnabled() { return alertsEnabled; }
    public long getAlertCooldown() { return alertCooldown; }
    public boolean isVerboseAlerts() { return verboseAlerts; }
    public boolean isAsyncChecks() { return asyncChecks; }
    public boolean isVirtualThreads() { return virtualThreads; }
    public Set<String> getExemptGamemodes() { return exemptGamemodes; }
    public List<String> getExemptWorlds() { return exemptWorlds; }
    public boolean isDiscordEnabled() { return discordEnabled; }
    public String getDiscordWebhookUrl() { return discordWebhookUrl; }
    public String getAdminPermission() { return adminPermission; }
    public String getBanCommand() { return banCommand; }
    public String getBanDuration() { return banDuration; }
    public String getBanReason() { return banReason; }
    public String getUnbanCommand() { return unbanCommand; }
    public boolean isAutobanEnabled() { return autobanEnabled; }
    public boolean isPersistGlobally() { return persistGlobally; }

    /**
     * Parses a duration string like "3d", "30m", "12h", "2w", "45s" into
     * milliseconds. Returns 0 if the format is invalid.
     */
    public long getBanDurationMs() {
        return parseDuration(banDuration);
    }

    /**
     * Parses a duration string like "3d", "30m", "12h", "2w", "45s" into
     * milliseconds. Returns 0 if the format is invalid.
     */
    public static long parseDuration(String duration) {
        if (duration == null || duration.isBlank()) return 0;
        String s = duration.trim().toLowerCase();
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == 0 || i >= s.length()) return 0;
        try {
            long amount = Long.parseLong(s.substring(0, i));
            char unit = s.charAt(i);
            return switch (unit) {
                case 's' -> amount * 1000L;
                case 'm' -> amount * 60_000L;
                case 'h' -> amount * 3_600_000L;
                case 'd' -> amount * 86_400_000L;
                case 'w' -> amount * 604_800_000L;
                default -> 0;
            };
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public String getPrefix() {
        return prefix;
    }

    public CheckConfig getCheckConfig(String check) {
        return checkConfigs.get(check);
    }

    public boolean isCheckEnabled(String check) {
        CheckConfig cc = checkConfigs.get(check);
        return cc != null && cc.enabled;
    }

    public record CheckConfig(
        boolean enabled,
        boolean autoban,
        int threshold,
        int decay,
        int maxViolations,
        double sensitivity,
        List<String> actions,
        int setbacksToBan,
        int kicksToBan,
        long setbackInterval
    ) {}
}
