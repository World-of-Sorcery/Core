package me.hektortm.wosCore.discord;

import me.hektortm.wosCore.WoSCore;
import me.hektortm.wosCore.database.StackTraceDAO;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;

import static me.hektortm.wosCore.WoSCore.jda;

public class DiscordLogger {
    private static final WoSCore plugin = WoSCore.getPlugin(WoSCore.class);
    private static final boolean DEV_ENV;
    static {
        String env = plugin.getConfig().getString("env");
        DEV_ENV = env != null && env.equalsIgnoreCase("dev");
    }
    private static final String ERROR_CHANNEL_ID = "1380839940123791460";
    private static final String WARNING_CHANNEL_ID = "1380840054640869508";
    private static final String INFO_CHANNEL_ID = "1413623246406029452";

    private static final String DEV_ERROR_CHANNEL_ID = "1413639964121366618";
    private static final String DEV_WARNING_CHANNEL_ID = "1413640000620331079";
    private static final String DEV_INFO_CHANNEL_ID = "1413638430029643866";

    // Every log's DB insert + embed send runs off the calling thread on a single daemon
    // thread, so log() never performs JDBC or HTTP on a hot (often main-thread) path.
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WoSCore-DiscordLogger");
        t.setDaemon(true);
        return t;
    });

    // Logs emitted before the gateway is CONNECTED (e.g. during onEnable, or while Discord
    // is unreachable) are buffered here and flushed by flushPending() on ReadyEvent.
    // Bounded: offer() drops the incoming entry once MAX_PENDING is reached.
    private static final int MAX_PENDING = 200;
    private static final LinkedBlockingQueue<DiscordLog> PENDING = new LinkedBlockingQueue<>(MAX_PENDING);

    // One reused StackTraceDAO, created lazily once the DatabaseManager exists.
    private static volatile StackTraceDAO stackTraceDAO;

    public static void log(DiscordLog log) {
        Level level = log.getLevel();
        if (level != Level.SEVERE && level != Level.WARNING && level != Level.INFO) {
            return; // only these three levels map to a channel
        }

        JDA current = jda;
        if (current == null || current.getStatus() != JDA.Status.CONNECTED) {
            // Gateway not ready — buffer and return (silently drop if the buffer is full).
            PENDING.offer(log);
            return;
        }
        EXECUTOR.submit(() -> dispatch(log));
    }

    /** Flush buffered logs once the gateway is ready. Invoked from JDA's ReadyEvent. */
    public static void flushPending() {
        DiscordLog log;
        while ((log = PENDING.poll()) != null) {
            final DiscordLog pending = log;
            EXECUTOR.submit(() -> dispatch(pending));
        }
    }

    private static StackTraceDAO stackTraceDAO() {
        StackTraceDAO local = stackTraceDAO;
        if (local == null) {
            synchronized (DiscordLogger.class) {
                local = stackTraceDAO;
                if (local == null) {
                    local = new StackTraceDAO(WoSCore.getPlugin(WoSCore.class).getDatabaseManager());
                    stackTraceDAO = local;
                }
            }
        }
        return local;
    }

    private static void dispatch(DiscordLog log) {
        String channelId;
        String title;
        int color;

        if (log.getLevel() == Level.SEVERE) {
            if (DEV_ENV) channelId = DEV_ERROR_CHANNEL_ID;
            else channelId = ERROR_CHANNEL_ID;
            title = "Error";
            color = 0xdb2525;
        } else if (log.getLevel() == Level.WARNING) {
            if (DEV_ENV) channelId = DEV_WARNING_CHANNEL_ID;
            else channelId = WARNING_CHANNEL_ID;
            title = "Warning";
            color = 0xe6e025;
        } else if (log.getLevel() == Level.INFO) {
            if (DEV_ENV) channelId = DEV_INFO_CHANNEL_ID;
            else channelId = INFO_CHANNEL_ID;
            title = "Info";
            color = 0x25db4f;
        } else {
            return;
        }

        JavaPlugin logPlugin = log.getPlugin();
        String pluginName = logPlugin.getName();
        String pluginVersion = "v"+logPlugin.getPluginMeta().getVersion();
        String message = log.getMessage();
        String uuid = log.getUuid();

        try {
            JDA current = jda;
            TextChannel channel = current == null ? null : current.getTextChannelById(channelId);
            if (channel == null) {
                return;
            }
            String apiUrl = "";
            if (log.getLevel() == Level.SEVERE) {
                String stacktrace = getStackTraceAsString(log.getException());
                UUID apiUUID = UUID.randomUUID();

                stackTraceDAO().addStacktrace(apiUUID.toString(), message, stacktrace, uuid, pluginName);


                if (DEV_ENV) apiUrl = "http://localhost:3001/api/stacktrace/"+apiUUID;
                else apiUrl = "https://api.worldofsorcery.com/api/stacktrace/"+apiUUID;
            }


            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");
            String formattedTime = LocalDateTime.now().format(formatter);

            EmbedBuilder embed = new EmbedBuilder();
            embed.setAuthor("Debug | "+title + (DEV_ENV ? " | Localhost" : ""));
            embed.setDescription(message);
            embed.addField("Plugin", pluginName, true);
            embed.addField("Version", pluginVersion, true);
            embed.addField("uuid", uuid, true);
            if (log.getLevel() == Level.SEVERE) embed.addField("Stacktrace", "[View Stacktrace](" + apiUrl + ")", false);
            embed.setFooter("Dev Logging • " + formattedTime);
            embed.setColor(color);

            channel.sendMessageEmbeds(embed.build()).queue();
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.SEVERE, "An error occurred while sending the message: " + e.getMessage());
            e.printStackTrace();
        }

    }

    private static String getStackTraceAsString(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append(throwable.toString()).append("\n");
        for (StackTraceElement element : throwable.getStackTrace()) {
            sb.append("  at ").append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}
