package dev.nyx.storage;

import java.util.UUID;

/**
 * A persistent, database-backed ban record.
 *
 * @param playerUuid the unique id of the banned player
 * @param playerName the player's name at the time of the ban
 * @param check      the check that triggered the ban
 * @param reason     human-readable reason for the ban
 * @param bannedAt   epoch millis when the ban was issued
 * @param expiresAt  epoch millis when the ban expires (Long.MAX_VALUE = permanent)
 */
public record BanRecord(UUID playerUuid, String playerName, String check, String reason, long bannedAt, long expiresAt) {

    public boolean isActive() {
        return System.currentTimeMillis() < expiresAt;
    }
}
