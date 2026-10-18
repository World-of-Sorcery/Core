package me.hektortm.wosCore;

import me.hektortm.wosCore.database.DatabaseManager;
import me.hektortm.wosCore.database.LoggingDAO;
import me.hektortm.wosCore.database.PlayerdataDAO;
import me.hektortm.wosCore.database.StackTraceDAO;
import me.hektortm.wosCore.discord.DiscordListener;
import me.hektortm.wosCore.discord.DiscordLog;
import me.hektortm.wosCore.discord.DiscordLogger;
import me.hektortm.wosCore.discord.command.DiscordCommand;
import me.hektortm.wosCore.listeners.JoinListener;
import me.hektortm.wosCore.listeners.WhitelistLogin;
import me.hektortm.wosCore.logging.LogManager;
import me.hektortm.wosCore.logging.command.DebugCommand;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;


@SuppressWarnings({"unused", "SpellCheckingInspection"})
public final class WoSCore extends JavaPlugin {
    private static WoSCore instance;
    private DatabaseManager dbManager;
    private me.hektortm.wosCore.api.WosApi api;
    private me.hektortm.wosCore.presence.PresenceReporter presence;
    private me.hektortm.wosCore.presence.NetworkOnline networkOnline;
    private me.hektortm.wosCore.server.ServerRegistrar registrar;
    private static me.hektortm.wosCore.server.ServerIdentity identity = me.hektortm.wosCore.server.ServerIdentity.defaults();
    private me.hektortm.wosCore.net.NetworkBus bus = me.hektortm.wosCore.net.NetworkBus.disabled();
    private LogManager logManager;
    private LangManager lang;
    private File langDirectory;
    private File playerDataFolder;
    public static JDA jda;
    public static final String REQUIRED_ROLE_ID = "1277277013945614346";

    @Override
    public void onEnable() {
        instance = this;
        try {
            identity = me.hektortm.wosCore.server.ServerIdentity.from(getConfig().getConfigurationSection("server"), System.getenv());
        } catch (IllegalArgumentException e) {
            getLogger().severe("Invalid server identity, Core stays disabled: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Server identity: id=" + identity.id() + ", tag=" + identity.tag() + ", realm=" + identity.realm());
        // Redis message bus between servers; env WOS_REDIS_URL wins over redis.url. Empty = disabled. The URL is never logged.
        bus = me.hektortm.wosCore.net.NetworkBus.create(
                me.hektortm.wosCore.net.NetworkBus.resolveUrl(System.getenv("WOS_REDIS_URL"), getConfig().getString("redis.url", "")),
                identity.id(), getLogger());
        // The proxy's channel for "send this player to server X" (ServerConnect); registered once here.
        getServer().getMessenger().registerOutgoingPluginChannel(this, me.hektortm.wosCore.net.ServerConnect.CHANNEL);

        try {
            // build() is non-blocking: JDA connects to the Discord gateway on its own
            // thread, so server startup no longer waits on Discord. Logs emitted before
            // the gateway is ready are buffered by DiscordLogger and flushed on ReadyEvent.
            jda = JDABuilder.createDefault(getConfig().getString("BOT-TOKEN"))
                    .setStatus(OnlineStatus.ONLINE)
                    .setActivity(Activity.playing("Minecraft"))
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT) // Enable the MESSAGE_CONTENT intent
                    .addEventListeners(new DiscordListener(), new ListenerAdapter() {
                        @Override
                        public void onReady(ReadyEvent event) {
                            DiscordLogger.flushPending();
                        }
                    })
                    .build();
        } catch (Exception e) {
            jda = null;
            getLogger().warning("Discord bot could not be started; continuing without Discord logging: " + e.getMessage());
        }
        // wos-api: the Go service that owns content (and, progressively, all) data.
        api = new me.hektortm.wosCore.api.WosApi(
                getConfig().getString("api.url", "http://localhost:8080"),
                apiToken(),
                java.time.Duration.ofMillis(getConfig().getLong("api.timeout-ms", 5000)),
                getLogger()
        );

        PlayerdataDAO playerdataDAO;
        try {
            // Initialize database with credentials from config
            dbManager = new DatabaseManager(
                    getConfig().getString("database.host"),
                    getConfig().getInt("database.port"),
                    getConfig().getString("database.name"),
                    getConfig().getString("database.username"),
                    getConfig().getString("database.password")
            );

            // Initialize DAOs
            playerdataDAO = new PlayerdataDAO(dbManager);
            LoggingDAO loggingDAO = new LoggingDAO(dbManager);
            StackTraceDAO stackTraceDAO = new StackTraceDAO(dbManager);
            dbManager.registerDAO(playerdataDAO);
            dbManager.registerDAO(loggingDAO);
            dbManager.registerDAO(stackTraceDAO);
            dbManager.initializeAllDAOs();

            // Register other DAOs and components...

        } catch (SQLException e) {
            getLogger().severe("Failed to initialize database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return; // do not continue onEnable against a half-built state
        }
        this.langDirectory = new File(getDataFolder(), "lang");
        if(!langDirectory.exists()) {
            langDirectory.mkdirs();
        }
        this.playerDataFolder = new File(getDataFolder(), "playerdata");
        if (!playerDataFolder.exists()) {
            playerDataFolder.mkdirs();
        }

        // Construct LangManager once, after its directory exists.
        lang = new LangManager(this);
        logManager = new LogManager(lang, this);
        int langFileCount = lang.getActiveLangFileCount();

        saveDefaultConfig();
        Utils.init(lang);

        loadConfig();




        Bukkit.getPluginManager().registerEvents(new WhitelistLogin(), this);
        Bukkit.getPluginManager().registerEvents(new JoinListener(playerdataDAO), this);
        // Who is online, for the portal's "Online staff".
        presence = new me.hektortm.wosCore.presence.PresenceReporter(this, api);
        presence.start();
        // Who is online on the whole network (/msg, friends, the tab list).
        networkOnline = new me.hektortm.wosCore.presence.NetworkOnline(api);
        // Tell wos-api this server is up (id and tag come from the token), then keep doing so.
        String webhookUrl = System.getenv("WOS_WEBHOOK_URL");
        if (webhookUrl == null || webhookUrl.isBlank()) webhookUrl = getConfig().getString("server.webhook-url", "");
        registrar = new me.hektortm.wosCore.server.ServerRegistrar(this, api, webhookUrl, identity);
        presence.setOnHeartbeat(registrar::heartbeat);
        registrar.start();
        // Every WoS plugin's permission nodes, for the portal's node list.
        me.hektortm.wosCore.permissions.PermissionCatalog.schedule(this);
        commandReg("writelog", new DebugCommand(logManager, lang, this));
        commandReg("discord", new DiscordCommand(this));

        DiscordLogger.log(new DiscordLog(
                Level.INFO,
                this,
                "M:8fb72730",
                "Core v"+WoSCore.getPlugin(WoSCore.class).getPluginMeta().getVersion()+" successfully started with " + langFileCount + " active language files.", null
        ));
    }



    @Override
    public void onDisable() {
        reloadConfig();
        if (presence != null) presence.stop(bus.isEnabled()); // bus on: players still here keep their rows until they move
        if (networkOnline != null) networkOnline.close();
        bus.close();
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, me.hektortm.wosCore.net.ServerConnect.CHANNEL);
        try {
            dbManager.closeConnection();
        } catch (Exception e) {
            DiscordLogger.log(new DiscordLog(
                    Level.SEVERE,
                    this,
                    "M:f8f719e7",
                    "Failed to close database connection: ", e
            ));
        }
        if (jda != null) {
            jda.shutdown();
            try {
                // Allow at most 10 seconds for remaining requests to finish
                if (!jda.awaitShutdown(Duration.ofSeconds(10))) {
                    // Cancel all remaining requests if shutdown is not complete
                    jda.shutdownNow();
                    jda.awaitShutdown(); // Wait until shutdown is complete (indefinitely)
                }
            } catch (InterruptedException e) {
                // Handle the exception if the thread is interrupted during shutdown
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }
    }

    public void writeLog(String name, Level level, String message) {
        Logger LOGGER = Logger.getLogger(name);
        LOGGER.log(level, message);
    }

    /** This server's id and tag (set at enable; {@code live}/{@code live} if Core did not start). */
    public static me.hektortm.wosCore.server.ServerIdentity identity() {
        return identity;
    }

    /** The Redis message bus between servers; {@code NetworkBus.disabled()} when no Redis is configured. Handlers run off the main thread. */
    public me.hektortm.wosCore.net.NetworkBus network() {
        return bus;
    }

    /** Who is online on the whole network (wos-api); null only if Core did not start. Blocking fetches: never on the main thread. */
    public me.hektortm.wosCore.presence.NetworkOnline networkOnline() {
        return networkOnline;
    }

    /** The API token: env {@code WOS_API_TOKEN} wins over {@code api.token}. Never log it. */
    private String apiToken() {
        String env = System.getenv("WOS_API_TOKEN");
        return env != null && !env.isBlank() ? env.trim() : getConfig().getString("api.token", "");
    }

    private void loadConfig() {
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    public FileConfiguration getPlayerData(Player player) {
        return getPlayerData(player.getUniqueId(), player.getName());
    }

    public FileConfiguration getPlayerData(UUID uuid, String username) {
        File playerFile = new File(playerDataFolder, uuid.toString()+ ".yml");
        FileConfiguration playerData = YamlConfiguration.loadConfiguration(playerFile);

        if(!playerFile.exists()) {
            playerData.set("UUID", uuid.toString());
            playerData.set("Username", username);
            playerData.set("Joindate", new Date().toString());
            playerData.set("Lastonline", new Date().toString());
            savePlayerData(playerData, playerFile);
        }
        return playerData;
    }

    public void savePlayerData(FileConfiguration playerData, File playerFile) {
        try {
            playerData.save(playerFile);
        } catch (IOException e) {
            getLogger().severe("Could not save player data file for "+playerFile.getName());
        }
    }


    private void commandReg(String name, CommandExecutor exe) {
        if (getCommand(name) != null) {
            //noinspection DataFlowIssue
            getCommand(name).setExecutor(exe);
        } else {
            getLogger().severe("Command '"+ name + "' was not found in plugin.yml");
        }
    }



    /** @deprecated Lang files are no longer copied to the server; messages come from the jars and wos-api ({@link LangStore}). */
    @Deprecated
    public void addLangFile(Plugin plugin, String fileName) {
        try (InputStream inputStream = plugin.getResource("lang/" + fileName)) {
            if (inputStream == null) {
                plugin.getLogger().warning("Language file not found: " + fileName);
                return; // Exit the method if the file is not found
            }
            File targetFile = new File(langDirectory, fileName);
            Files.copy(inputStream, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to copy language file: " + fileName);
            e.printStackTrace();
        }
    }

    public boolean checkUnlockable(UUID uuid, String id) {
        File playerFile = new File(playerDataFolder, uuid.toString()+ ".yml");
        FileConfiguration playerData = YamlConfiguration.loadConfiguration(playerFile);

        String path = "unlockables";
        List<String> unlockableList = playerData.getStringList(path);
        if (unlockableList.contains(id)) {
            return true;
        }
        return false;
    }

    public LangManager getLang() {
        return lang;
    }
    /** The shared wos-api client (blocking calls — use off the main thread). */
    public me.hektortm.wosCore.api.WosApi getApi() {
        return api;
    }

    public DatabaseManager getDatabaseManager() {
        return dbManager;
    }
}


