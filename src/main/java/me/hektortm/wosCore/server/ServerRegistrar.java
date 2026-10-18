package me.hektortm.wosCore.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.hektortm.wosCore.api.ApiException;
import me.hektortm.wosCore.api.WosApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers this server with wos-api ({@code PUT /v1/server/self}): at enable
 * (started, not ready), one tick later when the server is ticking (ready), and
 * again with every presence heartbeat. The API takes the server's id and tag
 * from the token, never from the body, and answers them ({@code {"id","tag"}}):
 * when they differ from this server's own identity, a SEVERE line is logged once
 * per start (an older API answers 204 without a body: nothing is checked). When
 * the token belongs to the other realm (a staging server with a live token …),
 * Core and the WoS plugins are disabled as well (see {@link #check}).
 * All sends run off the main thread.
 */
public final class ServerRegistrar {
    static final String PATH = "/v1/server/self";
    private static final String WOS_PACKAGE = "me.hektortm.";

    /** The request body (snake_case on the wire). */
    record Body(String webhookUrl, Map<String, String> plugins, boolean started, boolean ready) {}

    private final Plugin plugin;
    private final WosApi api;
    private final String webhookUrl;
    private final ServerIdentity identity;
    private final AtomicBoolean mismatchLogged = new AtomicBoolean();
    private volatile boolean failing;

    public ServerRegistrar(Plugin plugin, WosApi api, String webhookUrl, ServerIdentity identity) {
        this.plugin = plugin;
        this.api = api;
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl;
        this.identity = identity;
    }

    /** Registers now (started, not ready) and marks ready one tick later. */
    public void start() {
        send(true, false);
        Bukkit.getScheduler().runTaskLater(plugin, () -> send(false, true), 1L);
    }

    /** Main thread: the 30 s refresh, called from the presence heartbeat. */
    public void heartbeat() {
        send(false, true);
    }

    /** Takes the plugin list on the calling (main) thread, sends from an async task. */
    private void send(boolean started, boolean ready) {
        Body body = new Body(webhookUrl, wosPlugins(), started, ready);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> push(body));
    }

    private void push(Body body) {
        JsonElement answer;
        try {
            answer = api.send("PUT", PATH, body);
            if (failing) plugin.getLogger().info("[Server] registering with wos-api works again.");
            failing = false;
        } catch (ApiException e) {
            if (!failing) plugin.getLogger().warning("[Server] could not register with wos-api: " + e.getMessage());
            failing = true;
            return;
        }
        Check check = check(identity, answer);
        if (check.message() == null || !mismatchLogged.compareAndSet(false, true)) return;
        plugin.getLogger().severe("[Server] " + check.message());
        if (check.disable()) {
            // On the main thread: the WoS plugins first (they depend on Core), then Core.
            Bukkit.getScheduler().runTask(plugin, this::disableWos);
        }
    }

    /** Disables every enabled WoS plugin and then Core itself, so none of them writes into the wrong realm. */
    private void disableWos() {
        var pm = Bukkit.getPluginManager();
        for (Plugin p : pm.getPlugins()) {
            if (p != plugin && p.isEnabled() && isWos(p)) pm.disablePlugin(p);
        }
        if (plugin.isEnabled()) pm.disablePlugin(plugin);
    }

    /**
     * What the answer of {@code PUT /v1/server/self} means: {@code message} is the SEVERE line
     * (null: nothing to say), {@code disable} is set when the token belongs to a server of the
     * other realm — this server's writes would land in the wrong realm, so Core and the WoS
     * plugins stop. A mismatch of id or tag within one realm, or an answer naming a tag this
     * Core does not know, is only logged. An older API (204 without a body) is not checked.
     * The unconfigured legacy server is {@code live}/{@code live}, so realm live.
     */
    record Check(@Nullable String message, boolean disable) {}

    static Check check(ServerIdentity me, @Nullable JsonElement answer) {
        String mismatch = mismatch(me, answer);
        if (mismatch == null) return new Check(null, false);
        JsonObject o = answer.getAsJsonObject();
        String id = string(o, "id");
        String tag = string(o, "tag");
        String theirRealm = realmOf(tag);
        if (theirRealm == null || theirRealm.equals(me.realm())) return new Check(mismatch, false);
        return new Check("Server realm mismatch: this server is " + me.id() + "/" + me.tag() + " (realm " + me.realm()
                + ") but its API token belongs to " + id + "/" + tag + " (realm " + theirRealm
                + ") — its writes would land in the wrong realm. Core and the WoS plugins are disabled."
                + " Set WOS_SERVER_ID/WOS_SERVER_TAG to match the token.", true);
    }

    /** The realm of a tag as the API names it; null for a tag this Core does not know. */
    private static @Nullable String realmOf(String tag) {
        if (!ServerIdentity.TAGS.contains(tag)) return null;
        return new ServerIdentity("x", tag).realm();
    }

    /**
     * The warning when the API's answer names another server than {@code me}; null when they
     * match or the answer has no id and tag (an older API answers 204 without a body).
     * The text holds ids and tags only, never the token.
     */
    static @Nullable String mismatch(ServerIdentity me, @Nullable JsonElement answer) {
        if (answer == null || !answer.isJsonObject()) return null;
        JsonObject o = answer.getAsJsonObject();
        String id = string(o, "id");
        String tag = string(o, "tag");
        if (id == null || tag == null) return null;
        if (id.equals(me.id()) && tag.equals(me.tag())) return null;
        return "Server id/tag mismatch: this server is " + me.id() + "/" + me.tag()
                + " but its API token belongs to " + id + "/" + tag
                + " — hand-off and messages will fail. Set WOS_SERVER_ID/WOS_SERVER_TAG to match the token.";
    }

    private static @Nullable String string(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
    }

    /** Every enabled plugin whose main class is under {@code me.hektortm.}: name to version. */
    static Map<String, String> wosPlugins() {
        Map<String, String> out = new TreeMap<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (!p.isEnabled()) continue;
            if (isWos(p)) out.put(p.getName(), p.getPluginMeta().getVersion());
        }
        return out;
    }

    static boolean isWos(Plugin p) {
        String main = p.getPluginMeta().getMainClass();
        return main != null && main.startsWith(WOS_PACKAGE);
    }
}
