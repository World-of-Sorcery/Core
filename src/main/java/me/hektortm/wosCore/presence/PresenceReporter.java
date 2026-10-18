package me.hektortm.wosCore.presence;

import me.hektortm.wosCore.api.ApiException;
import me.hektortm.wosCore.api.WosApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tells wos-api who is online (the portal's "Online staff" widget):
 * the whole list after every join and quit, every 30 seconds as a heartbeat,
 * and an empty list when the server stops. wos-api treats a list not
 * refreshed for 90 s as stale, so a crash can't leave anyone "online".
 *
 * <p>The list is taken on the main thread and sent from one background thread,
 * in order, so a join's report can't overtake the next quit's.</p>
 */
public final class PresenceReporter implements Listener {
    static final String PATH = "/v1/server/presence";
    private static final long HEARTBEAT_TICKS = 20L * 30;

    /** One online player, as reported. */
    public record Online(UUID uuid, String username) {}

    private final Plugin plugin;
    private final WosApi api;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WoS-Presence");
        t.setDaemon(true);
        return t;
    });
    private BukkitTask heartbeat;
    private volatile Runnable onHeartbeat;
    /** Whether the last report failed: warn once per outage, not every 30 s. */
    private volatile boolean failing;

    public PresenceReporter(Plugin plugin, WosApi api) {
        this.plugin = plugin;
        this.api = api;
    }

    /** Registers the join/quit listener and starts the heartbeat (also reports once now: a reload keeps players online). */
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        heartbeat = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            report(null);
            Runnable hook = onHeartbeat;
            if (hook != null) hook.run();
        }, 20L, HEARTBEAT_TICKS);
    }

    /** Runs on the main thread with every 30 s heartbeat (the server registration refresh). */
    public void setOnHeartbeat(Runnable hook) {
        this.onHeartbeat = hook;
    }

    /**
     * Stops the heartbeat and reports an empty server (blocking, on shutdown).
     * With the message bus on, players still online are left listed: the proxy moves them
     * to the next server, whose report takes their rows over (a switch, not a network
     * join), and rows nobody refreshes go stale after 90 s.
     */
    public void stop(boolean busEnabled) {
        if (heartbeat != null) heartbeat.cancel();
        sender.shutdown();
        try {
            if (!sender.awaitTermination(3, TimeUnit.SECONDS)) sender.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (emptiesOnStop(busEnabled, Bukkit.getOnlinePlayers().size())) send(List.of());
    }

    /** Whether a stopping server reports an empty list: always without the bus, else only when nobody is online. */
    static boolean emptiesOnStop(boolean busEnabled, int online) {
        return !busEnabled || online == 0;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        report(null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        report(event.getPlayer().getUniqueId()); // still listed while its quit event runs
    }

    /** Main thread: takes the list now and queues sending it. */
    private void report(@Nullable UUID leaving) {
        List<Online> online = Bukkit.getOnlinePlayers().stream()
                .map((Player p) -> new Online(p.getUniqueId(), p.getName()))
                .toList();
        List<Map<String, String>> players = body(online, leaving);
        try {
            sender.execute(() -> send(players));
        } catch (java.util.concurrent.RejectedExecutionException stopping) {
            // shutting down: stop() reports the empty server itself
        }
    }

    /** The report's players: everyone online except the one leaving. */
    static List<Map<String, String>> body(Collection<Online> online, @Nullable UUID leaving) {
        return online.stream()
                .filter(o -> !o.uuid().equals(leaving))
                .map(o -> Map.of("uuid", o.uuid().toString(), "username", o.username()))
                .toList();
    }

    private void send(List<Map<String, String>> players) {
        try {
            api.send("PUT", PATH, Map.of("players", players));
            if (failing) plugin.getLogger().info("[Presence] reporting who is online works again.");
            failing = false;
        } catch (ApiException e) {
            if (!failing) plugin.getLogger().warning("[Presence] could not report who is online: " + e.getMessage());
            failing = true;
        }
    }
}
