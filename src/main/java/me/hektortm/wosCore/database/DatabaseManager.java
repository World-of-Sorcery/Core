package me.hektortm.wosCore.database;

import me.hektortm.wosCore.WoSCore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.hektortm.wosCore.discord.DiscordLog;
import me.hektortm.wosCore.discord.DiscordLogger;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class DatabaseManager {
    private final HikariDataSource dataSource;
    private final WoSCore plugin;
    private final List<IDAO> daoList = new ArrayList<>();

    public DatabaseManager(String host, int port, String database,
                           String username, String password) throws SQLException {
        this.plugin = WoSCore.getPlugin(WoSCore.class);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        config.setUsername(username);
        config.setPassword(password);

        // Pool settings
        config.setPoolName("WoSCore-Pool");
        config.setMaximumPoolSize(15);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setLeakDetectionThreshold(10000); // Reduced to detect leaks faster

        // MySQL optimizations
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        this.dataSource = new HikariDataSource(config);

        // Test connection
        try (Connection testConn = dataSource.getConnection()) {
            DiscordLogger.log(new DiscordLog(
                    Level.INFO,
                    plugin,
                    "DB:a82db1d8",
                    "Successfully connected to MySQL database with connection pooling.", null
            ));
        }
    }

    public void registerDAO(IDAO dao) {
        daoList.add(dao);
    }

    public void initializeAllDAOs() throws SQLException {
        for (IDAO dao : daoList) {
            try (Connection conn = dataSource.getConnection()) {
                dao.initializeTable();
            }
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void closeConnection() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            DiscordLogger.log(new DiscordLog(
                    Level.INFO,
                    plugin,
                    "DB:dac647b4",
                    "Database connection pool has been closed.",null
            ));
        }
    }
}