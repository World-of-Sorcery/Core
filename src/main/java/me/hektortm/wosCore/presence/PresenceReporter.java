package me.hektortm.wosCore.presence;

import com.destroystokyo.paper.profile.ProfileProperty;
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

    /**
     * One online player, as reported, with their skin (the signed "textures" property the proxy
     * forwarded) so the other servers' tab lists can show their head; {@code null} when there is none.
     */
    public record Online(UUID uuid, String username, @Nullable String skinValue, @Nullable String skinSignature) {
        public Online(UUID uuid, String username) {
            this(uuid, username, null, null);
        }

        static Online of(Player p) {
            for (ProfileProperty prop : p.getPlayerProfile().getProperties()) {
                if (prop.getName().equals("textures")) {
                    return new Online(p.getUniqueId(), p.getName(), prop.getValue(), prop.getSignature());
                }
            }
            return new Online(p.getUniqueId(), p.getName());
        }
    }

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
                .map(Online::of)
                .toList();
        List<Map<String, Object>> players = body(online, leaving);
        try {
            sender.execute(() -> send(players));
        } catch (java.util.concurrent.RejectedExecutionException stopping) {
            // shutting down: stop() reports the empty server itself
        }
    }

    /** The report's players: everyone online except the one leaving, each with their skin when they have one. */
    static List<Map<String, Object>> body(Collection<Online> online, @Nullable UUID leaving) {
        return online.stream()
                .filter(o -> !o.uuid().equals(leaving))
                .map(PresenceReporter::row)
                .toList();
    }

    private static Map<String, Object> row(Online o) {
        if (o.skinValue() == null || o.skinValue().isEmpty()) {
            return Map.of("uuid", o.uuid().toString(), "username", o.username());
        }
        Map<String, String> skin = new java.util.HashMap<>();
        skin.put("value", o.skinValue());
        if (o.skinSignature() != null) skin.put("signature", o.skinSignature());
        return Map.of("uuid", o.uuid().toString(), "username", o.username(), "skin", skin);
    }

    private void send(List<Map<String, Object>> players) {
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
