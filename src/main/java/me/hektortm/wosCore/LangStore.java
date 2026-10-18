package me.hektortm.wosCore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.hektortm.wosCore.api.ApiException;
import me.hektortm.wosCore.api.WosApi;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Every plugin's messages, in memory. One store is shared by all {@link LangManager}s
 * ({@link LangManager#shared()}).
 *
 * <p>Defaults come from each plugin's jar ({@code lang/<file>.yml}); nothing is
 * written to the server. Once the server has started, each plugin's defaults
 * are pushed to wos-api ({@code PUT /v1/server/lang/<plugin>}), where staff edit
 * them in the AdminPortal; the edits are then loaded ({@code GET /v1/content/lang})
 * and win over the defaults. Edits are reloaded when the portal signals a change
 * ({@link #reloadEdits()}). Without wos-api the defaults still work.</p>
 *
 * <p>Edits a server still has in its old lang files ({@code plugins/WoSCore/lang})
 * are sent along once and taken over where staff haven't edited the message; the
 * file is then renamed to {@code .yml.imported}.</p>
 */
public final class LangStore {

    private static final long RETRY_TICKS = 20L * 60; // a failed push is retried after a minute

    /** file → key → default, from the jars. */
    private final Map<String, Map<String, String>> defaults = new ConcurrentHashMap<>();
    /** plugin → its files. */
    private final Map<String, Set<String>> filesByPlugin = new ConcurrentHashMap<>();
    /** plugin → file → key → text its old lang file had changed from the default. */
    private final Map<String, Map<String, Map<String, String>>> oldEdits = new ConcurrentHashMap<>();
    /** Plugins whose push is scheduled. */
    private final Set<String> pushScheduled = ConcurrentHashMap.newKeySet();
    /** file → key → staff edit, replaced whole on each reload. */
    private volatile Map<String, Map<String, String>> edits = Map.of();

    /** Plugins use the shared store, {@link LangManager#shared()}; a new one is for tests. */
    LangStore() {}

    /** For tests: set a file's defaults / the edits without jars or wos-api. */
    void putDefaults(String file, Map<String, String> fileDefaults) {
        defaults.put(file, fileDefaults);
    }

    void setEdits(Map<String, Map<String, String>> next) {
        edits = next;
    }

    // ── Reading ─────────────────────────────────────────────────────────────────

    /** The message: the staff edit, else the default; null if the file or key doesn't exist. */
    @Nullable
    public String message(String file, String key) {
        Map<String, String> edited = edits.get(file);
        String value = edited == null ? null : edited.get(key);
        if (value != null) return value;
        Map<String, String> fileDefaults = defaults.get(file);
        return fileDefaults == null ? null : fileDefaults.get(key);
    }

    public boolean hasFile(String file) {
        return defaults.containsKey(file) || edits.containsKey(file);
    }

    public Set<String> files() {
        return Collections.unmodifiableSet(defaults.keySet());
    }

    /** A file's messages as they apply now (defaults with edits on top), key → text. */
    public Map<String, String> effective(String file) {
        Map<String, String> out = new LinkedHashMap<>(defaults.getOrDefault(file, Map.of()));
        out.putAll(edits.getOrDefault(file, Map.of()));
        return out;
    }

    // ── Registering a plugin's defaults ─────────────────────────────────────────

    /**
     * Reads {@code lang/<file>.yml} from the plugin's jar as its defaults and
     * schedules the plugin's push. False if the jar has no such file.
     */
    public boolean register(Plugin source, String file, WoSCore core) {
        Map<String, String> fileDefaults;
        try (InputStream in = source.getResource("lang/" + file + ".yml")) {
            if (in == null) return false;
            fileDefaults = flatten(in);
        } catch (Exception e) {
            core.getLogger().warning("[Lang] Could not read lang/" + file + ".yml from " + source.getName() + ": " + e.getMessage());
            return false;
        }
        defaults.put(file, fileDefaults);
        filesByPlugin.computeIfAbsent(source.getName(), k -> ConcurrentHashMap.newKeySet()).add(file);
        rememberOldEdits(source.getName(), file, fileDefaults, core);
        schedulePush(source.getName(), core);
        return true;
    }

    /** Every message of a YAML file, dotted key → text ("prefix.error" → "…"). */
    static Map<String, String> flatten(InputStream yaml) throws Exception {
        try (Reader reader = new InputStreamReader(yaml, StandardCharsets.UTF_8)) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(reader);
            Map<String, String> out = new LinkedHashMap<>();
            for (String key : config.getKeys(true)) {
                if (config.isConfigurationSection(key)) continue;
                String value = config.getString(key);
                if (value != null) out.put(key, value);
            }
            return out;
        }
    }

    /** Texts the server's old lang file changed from the defaults (to send once). */
    private void rememberOldEdits(String plugin, String file, Map<String, String> fileDefaults, WoSCore core) {
        File old = oldFile(core, file);
        if (!old.isFile()) return;
        try (InputStream in = java.nio.file.Files.newInputStream(old.toPath())) {
            Map<String, String> changed = new HashMap<>();
            flatten(in).forEach((key, value) -> {
                String def = fileDefaults.get(key);
                if (def != null && !def.equals(value)) changed.put(key, value);
            });
            if (!changed.isEmpty()) oldEdits.computeIfAbsent(plugin, k -> new ConcurrentHashMap<>()).put(file, changed);
        } catch (Exception e) {
            core.getLogger().warning("[Lang] Could not read old " + old.getName() + ": " + e.getMessage());
        }
    }

    private static File oldFile(WoSCore core, String file) {
        return new File(core.getDataFolder(), "lang/" + file + ".yml");
    }

    // ── Push and reload ─────────────────────────────────────────────────────────

    /**
     * Pushes the plugin's defaults once the server is running (the first tick
     * runs after every plugin's onEnable, so all of its files are registered).
     */
    private void schedulePush(String plugin, WoSCore core) {
        if (!pushScheduled.add(plugin)) return;
        Bukkit.getScheduler().runTaskLater(core, () ->
                Bukkit.getScheduler().runTaskAsynchronously(core, () -> push(plugin, core)), 1L);
    }

    private void push(String plugin, WoSCore core) {
        WosApi api = core.getApi();
        Logger log = core.getLogger();
        Map<String, Map<String, String>> files = new HashMap<>();
        for (String file : filesByPlugin.getOrDefault(plugin, Set.of())) files.put(file, defaults.get(file));
        Map<String, Map<String, String>> old = oldEdits.getOrDefault(plugin, Map.of());

        JsonObject body = new JsonObject();
        body.add("files", WosApi.GSON.toJsonTree(files));
        body.add("edits", WosApi.GSON.toJsonTree(old));
        try {
            api.send("PUT", "/v1/server/lang/" + plugin, body);
        } catch (ApiException e) {
            log.warning("[Lang] Could not send " + plugin + "'s messages to wos-api (" + e.getMessage()
                    + "); using the defaults, trying again in a minute.");
            Bukkit.getScheduler().runTaskLaterAsynchronously(core, () -> push(plugin, core), RETRY_TICKS);
            return;
        }
        // Old edits are in wos-api now: don't send them again.
        for (String file : old.keySet()) {
            File f = oldFile(core, file);
            if (!f.renameTo(new File(f.getParentFile(), f.getName() + ".imported"))) {
                log.warning("[Lang] Could not rename " + f.getName() + " after taking over its edits.");
            }
        }
        oldEdits.remove(plugin);
        log.info("[Lang] " + plugin + ": " + files.size() + " message file(s) sent to wos-api.");
        reloadEdits(api, log);
    }

    /** Loads the staff edits from wos-api (blocking: call off the main thread). */
    public void reloadEdits() {
        WoSCore core = WoSCore.getPlugin(WoSCore.class);
        reloadEdits(core.getApi(), core.getLogger());
    }

    private void reloadEdits(WosApi api, Logger log) {
        try {
            JsonElement rows = api.getJson("/v1/content/lang");
            Map<String, Map<String, String>> next = new HashMap<>();
            if (rows != null && rows.isJsonArray()) {
                for (JsonElement el : rows.getAsJsonArray()) {
                    JsonObject row = el.getAsJsonObject();
                    JsonElement value = row.get("value");
                    if (value == null || value.isJsonNull()) continue;
                    next.computeIfAbsent(row.get("file").getAsString(), k -> new HashMap<>())
                            .put(row.get("key").getAsString(), value.getAsString());
                }
            }
            edits = next;
        } catch (ApiException e) {
            log.warning("[Lang] Could not load message edits from wos-api: " + e.getMessage());
        }
    }
}
