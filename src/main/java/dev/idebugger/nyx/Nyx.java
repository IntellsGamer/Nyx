package dev.idebugger.nyx;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import dev.idebugger.nyx.alert.AlertManager;
import dev.idebugger.nyx.alert.DiscordWebhook;
import dev.idebugger.nyx.checks.CheckManager;
import dev.idebugger.nyx.commands.NyxCommand;
import dev.idebugger.nyx.data.PlayerDataManager;
import dev.idebugger.nyx.data.NyxPlayerData;
import dev.idebugger.nyx.gui.ChecksGUI;
import dev.idebugger.nyx.listener.PacketListener;
import dev.idebugger.nyx.punishment.PunishmentManager;
import dev.idebugger.nyx.storage.BanManager;
import dev.idebugger.nyx.storage.StorageManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Nyx extends JavaPlugin implements Listener {

    private static Nyx instance;
    private NyxConfig config;
    private CheckManager checkManager;
    private PlayerDataManager playerDataManager;
    private AlertManager alertManager;
    private PunishmentManager punishmentManager;
    private DiscordWebhook discordWebhook;
    private ChecksGUI checksGUI;
    private MiniMessage miniMessage;
    private ScheduledExecutorService executorService;
    private StorageManager storageManager;
    private BanManager banManager;

    public static Nyx get() {
        return instance;
    }

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings().checkForUpdates(false).bStats(false);
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        instance = this;
        this.miniMessage = MiniMessage.miniMessage();

        saveDefaultConfig();
        saveResource("messages.yml", false);
        this.config = new NyxConfig(this);

        this.checkManager = new CheckManager();
        this.playerDataManager = new PlayerDataManager();

        int poolSize = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.executorService = Executors.newScheduledThreadPool(
            poolSize,
            config.isVirtualThreads()
                ? Thread.ofVirtual().name("nyx-", 0).factory()
                : r -> new Thread(r, "nyx-worker")
        );

        this.alertManager = new AlertManager(this);
        this.punishmentManager = new PunishmentManager(this);
        this.discordWebhook = new DiscordWebhook(this);
        this.checksGUI = new ChecksGUI(this);

        if (config.isPersistGlobally()) {
            this.storageManager = new StorageManager(this);
            this.storageManager.init();
            this.banManager = new BanManager(this, this.storageManager);
        }

        getServer().getPluginManager().registerEvents(this, this);

        PacketEvents.getAPI().init();

        PacketListener packetListener = new PacketListener(this);
        packetListener.register();

        getCommand("nyx").setExecutor(new NyxCommand(this));

        getLogger().info("+------------------------------------------+");
        getLogger().info("|  Nyx v" + getDescription().getVersion() + " Enabled            |");
        getLogger().info("|  Nullify Your Xploits                    |");
        getLogger().info("+------------------------------------------+");

        startViolationDecayTask();
    }

    @Override
    public void onDisable() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            try {
                executorService.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        PacketEvents.getAPI().terminate();
        if (checksGUI != null) checksGUI.unregister();
        if (storageManager != null) {
            persistAllViolations();
            storageManager.close();
        }
        playerDataManager.clearAll();
        instance = null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        NyxPlayerData data = playerDataManager.createData(player);
        if (storageManager != null) {
            seedPersistentViolations(data);
            seedPersistentEscalation(data);
            if (banManager != null) {
                banManager.applyPersistentBan(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (storageManager != null) {
            persistPlayerViolations(player.getUniqueId());
        }
        playerDataManager.removeData(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        NyxPlayerData data = playerDataManager.getData(player);
        if (data != null) {
            data.setLastGamemodeChangeTime(System.currentTimeMillis());
            data.resetAccumulatedPacketFall();
            data.setWurstPatternDetected(false);
        }
    }

    private void startViolationDecayTask() {
        getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            playerDataManager.getAllData().forEach((uuid, data) -> data.tickViolations());
        }, 20L, 20L);
    }

    public NyxConfig getNyxConfig() { return config; }
    public CheckManager getCheckManager() { return checkManager; }
    public PlayerDataManager getPlayerDataManager() { return playerDataManager; }
    public AlertManager getAlertManager() { return alertManager; }
    public PunishmentManager getPunishmentManager() { return punishmentManager; }
    public DiscordWebhook getDiscordWebhook() { return discordWebhook; }
    public ChecksGUI getChecksGUI() { return checksGUI; }
    public MiniMessage getMiniMessage() { return miniMessage; }
    public ScheduledExecutorService getExecutorService() { return executorService; }
    public StorageManager getStorageManager() { return storageManager; }
    public BanManager getBanManager() { return banManager; }

    private void seedPersistentViolations(NyxPlayerData data) {
        if (storageManager == null) return;
        storageManager.getViolations(data.getUuid()).forEach(data::setViolationFromStorage);
    }

    private void seedPersistentEscalation(NyxPlayerData data) {
        if (storageManager == null) return;
        storageManager.getEscalationCounts(data.getUuid())
            .forEach((check, counts) -> data.restoreEscalation(check, counts.setbacks(), counts.kicks()));
    }

    private void persistPlayerViolations(UUID uuid) {
        if (storageManager == null) return;
        NyxPlayerData data = playerDataManager.getData(uuid);
        if (data == null) return;
        persistViolations(data);
        persistEscalationCounts(data);
    }

    private void persistViolations(NyxPlayerData data) {
        data.getViolationMap().forEach((check, vl) -> {
            if (vl > 0) {
                storageManager.setViolation(data.getUuid(), check, vl);
            }
        });
    }

    private void persistEscalationCounts(NyxPlayerData data) {
        java.util.Set<String> checks = new java.util.HashSet<>();
        checks.addAll(data.getSetbackCountMap().keySet());
        checks.addAll(data.getKickCountMap().keySet());
        for (String check : checks) {
            storageManager.setEscalation(data.getUuid(), check,
                data.getSetbackCount(check), data.getKickCount(check));
        }
    }

    private void persistAllViolations() {
        if (storageManager == null) return;
        playerDataManager.getAllData().forEach((uuid, data) -> {
            persistViolations(data);
            persistEscalationCounts(data);
        });
    }
}
