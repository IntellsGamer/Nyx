package dev.nyx.data;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerDataManager {

    private final ConcurrentHashMap<UUID, NyxPlayerData> playerDataMap;

    public PlayerDataManager() {
        this.playerDataMap = new ConcurrentHashMap<>();
    }

    public NyxPlayerData getData(Player player) {
        return playerDataMap.computeIfAbsent(player.getUniqueId(), id -> new NyxPlayerData(player));
    }

    public NyxPlayerData getData(UUID uuid) {
        return playerDataMap.get(uuid);
    }

    public NyxPlayerData createData(Player player) {
        return playerDataMap.computeIfAbsent(player.getUniqueId(), id -> new NyxPlayerData(player));
    }

    public void removeData(Player player) {
        playerDataMap.remove(player.getUniqueId());
    }

    public void removeData(UUID uuid) {
        playerDataMap.remove(uuid);
    }

    public boolean hasData(Player player) {
        return playerDataMap.containsKey(player.getUniqueId());
    }

    public boolean hasData(UUID uuid) {
        return playerDataMap.containsKey(uuid);
    }

    public Map<UUID, NyxPlayerData> getAllData() {
        return playerDataMap;
    }

    public void clearAll() {
        playerDataMap.clear();
    }

    public int getSize() {
        return playerDataMap.size();
    }
}
