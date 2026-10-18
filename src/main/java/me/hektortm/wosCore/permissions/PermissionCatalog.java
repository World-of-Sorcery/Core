package me.hektortm.wosCore.permissions;

import me.hektortm.wosCore.WoSCore;
import me.hektortm.wosCore.api.ApiException;
import me.hektortm.wosCore.api.WosApi;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Sends every WoS plugin's permission nodes to wos-api
 * ({@code PUT /v1/server/permissions/<plugin>}), so the AdminPortal can list
 * and explain them next to its own nodes.
 *
 * <p>The nodes are what each plugin declares in its {@code plugin.yml}: the
 * {@code permissions:} section (with descriptions) and every command's
 * {@code permission:}. A WoS plugin is Core itself or one that depends on it.
 * Sent once the server is running (the first tick runs after every plugin's
 * onEnable); a failed send is retried after a minute.</p>
 */
public final class PermissionCatalog {

    private static final long RETRY_TICKS = 20L * 60;

    /** One node as the API takes it. */
    record Node(String node, String description, String defaultValue) {}

    private PermissionCatalog() {}

    /** Schedules the send of every WoS plugin's nodes. */
    public static void schedule(WoSCore core) {
        Bukkit.getScheduler().runTaskLater(core, () -> {
            for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                if (!isWos(plugin, core.getName())) continue;
                List<Node> nodes = nodesOf(plugin.getDescription());
                Bukkit.getScheduler().runTaskAsynchronously(core, () -> push(core, plugin.getName(), nodes));
            }
        }, 1L);
    }

    static boolean isWos(Plugin plugin, String coreName) {
        PluginDescriptionFile d = plugin.getDescription();
        return plugin.getName().equals(coreName) || d.getDepend().contains(coreName) || d.getSoftDepend().contains(coreName);
    }

    /**
     * A plugin's nodes: its declared permissions, then each command permission
     * it does not declare ("Use /&lt;command&gt;.").
     */
    static List<Node> nodesOf(PluginDescriptionFile d) {
        Map<String, Node> out = new LinkedHashMap<>();
        for (Permission p : d.getPermissions()) {
            out.put(p.getName(), new Node(p.getName(), p.getDescription(), defaultName(p.getDefault())));
        }
        for (Map.Entry<String, Map<String, Object>> cmd : d.getCommands().entrySet()) {
            Object perm = cmd.getValue() == null ? null : cmd.getValue().get("permission");
            if (!(perm instanceof String node) || node.isBlank()) continue;
            for (String one : node.split(";")) { // Bukkit allows "a;b"
                String n = one.trim();
                if (!n.isEmpty()) out.putIfAbsent(n, new Node(n, "Use /" + cmd.getKey() + ".", "op"));
            }
        }
        return new ArrayList<>(out.values());
    }

    /** plugin.yml's spelling of a default ("op", "true", "false", "not op"). */
    static String defaultName(PermissionDefault d) {
        return switch (d) {
            case TRUE -> "true";
            case FALSE -> "false";
            case NOT_OP -> "not op";
            case OP -> "op";
        };
    }

    private static void push(WoSCore core, String plugin, List<Node> nodes) {
        WosApi api = core.getApi();
        Logger log = core.getLogger();
        if (api == null) return;
        try {
            api.send("PUT", "/v1/server/permissions/" + plugin, Map.of("nodes", nodes));
            log.info("[Permissions] " + plugin + ": " + nodes.size() + " node(s) sent to wos-api.");
        } catch (ApiException e) {
            log.warning("[Permissions] Could not send " + plugin + "'s nodes to wos-api (" + e.getMessage()
                    + "); trying again in a minute.");
            Bukkit.getScheduler().runTaskLaterAsynchronously(core, () -> push(core, plugin, nodes), RETRY_TICKS);
        }
    }
}
