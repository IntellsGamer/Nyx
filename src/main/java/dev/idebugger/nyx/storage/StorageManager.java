package dev.idebugger.nyx.storage;

import dev.idebugger.nyx.Nyx;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SQLite-backed persistent storage for global violation levels and bans.
 *
 * Using a self-contained {@code nyx.db} file means a cheater's accumulated
 * violation levels and bans survive a reconnect &amp; server restart rather
 * than resetting every time they log out.
 *
 * All access is guarded by a single lock because SQLite allows one writer at
 * a time, and the small operation size keeps contention negligible.
 */
public final class StorageManager {

    private final Nyx plugin;
    private final File dbFile;
    private final ReentrantLock lock = new ReentrantLock();

    private Connection connection;

    public StorageManager(Nyx plugin) {
        this.plugin = plugin;
        this.dbFile = new File(plugin.getDataFolder(), "nyx.db");
    }

    public void init() {
        try {
            // Driver is not auto-registered through the service loader once shaded
            // (META-INF/services is stripped), so load it explicitly.
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            createTables();
            plugin.getLogger().info("Storage: connected to " + dbFile.getName());
        } catch (Exception e) {
            plugin.getLogger().severe("Storage: failed to initialize SQLite: " + e.getMessage());
        }
    }

    private void createTables() throws SQLException {
        lock.lock();
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS violations (
                    player_uuid TEXT NOT NULL,
                    check_name  TEXT NOT NULL,
                    vl          INTEGER NOT NULL DEFAULT 0,
                    updated_at  INTEGER NOT NULL,
                    PRIMARY KEY (player_uuid, check_name)
                )
                """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS bans (
                    player_uuid TEXT PRIMARY KEY NOT NULL,
                    player_name TEXT NOT NULL,
                    check_name  TEXT NOT NULL,
                    reason      TEXT NOT NULL,
                    banned_at   INTEGER NOT NULL,
                    expires_at  INTEGER NOT NULL
                )
                """);
        } finally {
            lock.unlock();
        }
    }

    public Map<String, Integer> getViolations(UUID uuid) {
        Map<String, Integer> out = new HashMap<>();
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT check_name, vl FROM violations WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("check_name"), rs.getInt("vl"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Storage: failed to read violations: " + e.getMessage());
        } finally {
            lock.unlock();
        }
        return out;
    }

    public void setViolation(UUID uuid, String check, int vl) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO violations (player_uuid, check_name, vl, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(player_uuid, check_name)
                DO UPDATE SET vl = excluded.vl, updated_at = excluded.updated_at
                """)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, check);
            ps.setInt(3, vl);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Storage: failed to write violation: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public void removePlayer(UUID uuid) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM violations WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Storage: failed to remove player: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public void recordBan(BanRecord ban) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO bans (player_uuid, player_name, check_name, reason, banned_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid)
                DO UPDATE SET player_name = excluded.player_name,
                              check_name  = excluded.check_name,
                              reason      = excluded.reason,
                              banned_at   = excluded.banned_at,
                              expires_at  = excluded.expires_at
                """)) {
            ps.setString(1, ban.playerUuid().toString());
            ps.setString(2, ban.playerName());
            ps.setString(3, ban.check());
            ps.setString(4, ban.reason());
            ps.setLong(5, ban.bannedAt());
            ps.setLong(6, ban.expiresAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Storage: failed to record ban: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public BanRecord getActiveBan(UUID uuid) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT player_uuid, player_name, check_name, reason, banned_at, expires_at FROM bans WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BanRecord ban = new BanRecord(
                        uuid,
                        rs.getString("player_name"),
                        rs.getString("check_name"),
                        rs.getString("reason"),
                        rs.getLong("banned_at"),
                        rs.getLong("expires_at")
                    );
                    if (ban.isActive()) {
                        return ban;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Storage: failed to read ban: " + e.getMessage());
        } finally {
            lock.unlock();
        }
        return null;
    }

    public void clearBan(UUID uuid) {
        lock.lock();
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM bans WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Storage: failed to clear ban: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        lock.lock();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Storage: error closing connection: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }
}
