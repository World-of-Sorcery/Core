package me.hektortm.wosCore.listeners;

import me.hektortm.wosCore.WoSCore;
import me.hektortm.wosCore.database.PlayerdataDAO;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

public class JoinListener implements Listener {

    private final PlayerdataDAO dao;
    private final Plugin plugin;

    public JoinListener(PlayerdataDAO dao) {
        this.dao = dao;
        this.plugin = WoSCore.getPlugin(WoSCore.class);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Snapshot the immutable identity on the main thread, then do the single upsert
        // off-thread. Replaces the previous 3-4 synchronous main-thread queries.
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> dao.ensurePlayer(uuid, name));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> dao.updateLastOnline(player));
    }

}
