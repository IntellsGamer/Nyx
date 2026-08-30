package dev.nyx;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import dev.nyx.alert.AlertManager;
import dev.nyx.alert.DiscordWebhook;
import dev.nyx.checks.CheckManager;
import dev.nyx.commands.NyxCommand;
import dev.nyx.data.PlayerDataManager;
import dev.nyx.data.NyxPlayerData;
import dev.nyx.gui.ChecksGUI;
import dev.nyx.listener.PacketListener;
import dev.nyx.punishment.PunishmentManager;
import dev.nyx.storage.BanManager;
import dev.nyx.storage.StorageManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

    private void persistPlayerViolations(UUID uuid) {
        if (storageManager == null) return;
        NyxPlayerData data = playerDataManager.getData(uuid);
        if (data == null) return;
        data.getViolationMap().forEach((check, vl) -> {
            if (vl > 0) {
                storageManager.setViolation(uuid, check, vl);
            }
        });
    }

    private void persistAllViolations() {
        if (storageManager == null) return;
        playerDataManager.getAllData().forEach((uuid, data) -> {
            data.getViolationMap().forEach((check, vl) -> {
                if (vl > 0) {
                    storageManager.setViolation(uuid, check, vl);
                }
            });
        });
    }
}
