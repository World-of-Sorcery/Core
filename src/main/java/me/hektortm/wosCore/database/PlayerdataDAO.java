package me.hektortm.wosCore.database;

import me.hektortm.wosCore.WoSCore;
import me.hektortm.wosCore.discord.DiscordLog;
import me.hektortm.wosCore.discord.DiscordLogger;
import org.bukkit.entity.Player;
import java.sql.*;
import java.util.UUID;
import java.util.logging.Level;

public class PlayerdataDAO implements IDAO {
    private final DatabaseManager db;
    private final WoSCore plugin = WoSCore.getPlugin(WoSCore.class);
    private final String logName = "PlayerdataDAO";

    public PlayerdataDAO(DatabaseManager db) {
        this.db = db;
    }

    @Override
    public void initializeTable() throws SQLException {
        try (Connection conn = db.getConnection(); Statement statement = conn.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS playerdata (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(36) NOT NULL,
                    last_known_name VARCHAR(16) NOT NULL,
                    last_online TIMESTAMP NOT NULL
                )
            """);
        }
    }

    /**
     * Single-statement upsert used by the join path: inserts a new player (with
     * {@code last_online = now}) or, for an existing one, refreshes username/last_known_name
     * without touching last_online (quit owns that). Replaces the old
     * addPlayer/isInDatabase/getLastKnownName/updateUsername sequence — including the
     * double-insert when both hasPlayedBefore() and isInDatabase() were false.
     * Intended to be called off the main thread. (T13 ports the SQL to Postgres.)
     */
    public void ensurePlayer(UUID uuid, String name) {
        String sql = "INSERT INTO playerdata (uuid, username, last_known_name, last_online) VALUES (?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE username = VALUES(username), last_known_name = VALUES(last_known_name)";
        try (Connection conn = db.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, uuid.toString());
            pstmt.setString(2, name);
            pstmt.setString(3, name);
            pstmt.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            pstmt.executeUpdate();
        } catch (SQLException e) {
            DiscordLogger.log(new DiscordLog(
                    Level.SEVERE,
                    plugin,
                    "PD:ensure01",
                    "Failed to ensure Player: ", e
            ));
        }
    }

    public void addPlayer(Player player) {
        String sql = "INSERT INTO playerdata (uuid, username, last_known_name, last_online) VALUES (?, ?, ?, ?)";
        try (Connection conn = db.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, player.getUniqueId().toString());
            pstmt.setString(2, player.getName());
            pstmt.setString(3, player.getName());
            pstmt.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            pstmt.executeUpdate();
        } catch (SQLException e) {
            DiscordLogger.log(new DiscordLog(
                    Level.SEVERE,
                    plugin,
                    "PD:cfdff75d",
                    "Failed to add Player: ",e
            ));
        }
    }

    public boolean isInDatabase(Player player) {
        String sql = "SELECT 1 FROM playerdata WHERE uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, player.getUniqueId().toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            DiscordLogger.log(new DiscordLog(
                    Level.SEVERE,
                    plugin,
                    "PD:44387d4c",
                    "Failed to verify Playerdata: ",e
            ));
            return false;
        }
    }

    public void updateUsername(Player player) {
        String sql = "UPDATE playerdata SET username = ?, last_known_name = ? WHERE uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, player.getName());
            pstmt.setString(2, player.getName());
            pstmt.setString(3, player.getUniqueId().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            DiscordLogger.log(new DiscordLog(
                    Level.SEVERE,
                    plugin,
                    "PD:5af4f379",
                    "Failed to update Username: ",e
            ));
        }
    }

    public String getLastKnownName(Player player) {
        String sql = "SELECT last_known_name FROM playerdata WHERE uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, player.getUniqueId().toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("last_known_name");
                }
            }
        } catch (SQLException e) {
            DiscordLogger.log(new DiscordLog(
                    Level.SEVERE,
                    plugin,
                    "PD:c81eca38",
                    "Failed to get last known name: ",e
            ));
        }
        return null;
    }

    public void updateLastOnline(Player player) {
        String sql = "UPDATE playerdata SET last_online = ? WHERE uuid = ?";
        try (Connection conn = db.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
            pstmt.setString(2, player.getUniqueId().toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            DiscordLogger.log(new DiscordLog(
                    Level.SEVERE,
                    plugin,
                    "PD:f72c9c68",
                    "Failed to update last online time: ",e
            ));
        }
    }
}