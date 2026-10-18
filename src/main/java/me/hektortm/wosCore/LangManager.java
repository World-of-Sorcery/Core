package me.hektortm.wosCore;

import me.hektortm.wosCore.discord.DiscordLog;
import me.hektortm.wosCore.discord.DiscordLogger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Messages by file and key ("guis", "error.cooldown"). Every plugin may create
 * its own LangManager: they all read the one {@link LangStore}, which holds each
 * plugin's defaults (from its jar) and the staff edits (from wos-api, edited in
 * the AdminPortal). Nothing is written to or read from lang files on the server
 * any more, except that edits still in old files are taken over once.
 */
public class LangManager {
    /** The one message store every plugin's LangManager reads. */
    private static final LangStore SHARED = new LangStore();

    private final WoSCore plugin;
    private final LangStore store = SHARED;

    /** The shared message store (e.g. to reload the edits when the portal signals a change). */
    public static LangStore shared() {
        return SHARED;
    }

    public LangManager(WoSCore plugin) {
        this.plugin = plugin;
        loadLangFiles();
    }

    /** Registers WoSCore's own messages (general, debug). Safe to call more than once. */
    public void loadLangFiles() {
        loadInternalLangFiles("general");
        loadInternalLangFiles("debug");
    }

    private void loadInternalLangFiles(String filename) {
        if (store.hasFile(filename)) return;
        if (!store.register(plugin, filename, plugin)) {
            plugin.getLogger().warning("WoSCore's own lang/" + filename + ".yml is missing from its jar.");
        }
    }

    /**
     * Registers a plugin's messages: {@code lang/<filename>.yml} in that plugin's
     * jar holds the defaults; they're sent to wos-api once the server has started.
     */
    public void loadLangFileExternal(Plugin sourcePlugin, String filename, WoSCore corePlugin) {
        if (!store.register(sourcePlugin, filename, corePlugin)) {
            DiscordLogger.log(new DiscordLog(
                    Level.SEVERE,
                    plugin,
                    "LM:4a23cf71",
                    "The embedded resource 'lang/" + filename + ".yml' cannot be found in " + sourcePlugin.getName(),
                    new Exception("Resource not found")
            ));
        }
    }

    public String getMessage(String fileName, String key) {
        if (!store.hasFile(fileName)) {
            plugin.getLogger().warning("Language file '" + fileName + "' not found");
            return "File not found: " + fileName;
        }
        String message = store.message(fileName, key);
        if (message == null) {
            plugin.getLogger().warning("Message key '" + key + "' not found in file '" + fileName + "'");
            return "Message not found: " + key;
        }
        return Utils.replaceColorPlaceholders(message);
    }

    /** Reloads the staff edits from wos-api (in the background). */
    public void reload() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, store::reloadEdits);
    }

    /** Edits are loaded for every file at once: the same as {@link #reload()}. */
    public void reloadFile(String file) {
        reload();
    }

    /** How many message files are registered. */
    public int getActiveLangFileCount() {
        return store.files().size();
    }

    /** A file's messages as they apply now, as a configuration (key → text). */
    public FileConfiguration getConfig(String file) {
        YamlConfiguration config = new YamlConfiguration();
        store.effective(file).forEach(config::set);
        return config;
    }

    public List<String> getAllLangFilenames() {
        return new ArrayList<>(store.files());
    }
}
