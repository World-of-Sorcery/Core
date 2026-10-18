package me.hektortm.wosCore.database;

import me.hektortm.wosCore.WoSCore;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

public class StackTraceDAO implements IDAO{
    private final DatabaseManager db;
    private final WoSCore plugin = WoSCore.getPlugin(WoSCore.class);

    public StackTraceDAO(DatabaseManager db) {
        this.db = db;
    }


    @Override
    public void initializeTable() throws SQLException {
        try (Connection conn = db.getConnection(); Statement statement = conn.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS stacktraces (
                    uuid VARCHAR(36) PRIMARY KEY,
                    message TEXT NOT NULL,
                    trace TEXT NOT NULL,
                    traceuuid VARCHAR(36) NOT NULL,
                    date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    plugin VARCHAR(255) NOT NULL
                )
            """);
        }
    }

    public void addStacktrace(String uuid, String message, String trace, String traceuuid, String plugin) {
        String sql = "INSERT INTO stacktraces (uuid, message, trace, traceuuid, plugin) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = db.getConnection();
             var pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, uuid);
            pstmt.setString(2, message);
            pstmt.setString(3, trace);
            pstmt.setString(4, traceuuid);
            pstmt.setString(5, plugin);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to add Stacktrace: "+e);
        }
    }
}
