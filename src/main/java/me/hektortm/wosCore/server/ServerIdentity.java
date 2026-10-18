package me.hektortm.wosCore.server;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * Which server this is: its id and its tag ({@code hub|backend|staging|live}).
 * Read from {@code server.id} / {@code server.tag} in Core's config; the
 * environment ({@code WOS_SERVER_ID}, {@code WOS_SERVER_TAG}) overrides them.
 * Defaults are {@code live} / {@code live}, so today's servers need no change.
 *
 * <p>{@link #configured()} tells whether the tag was set at all (env or yaml).
 * A server without a tag is the old single server: it takes every message,
 * content reloads included. Only a configured {@code live} refuses them.</p>
 *
 * <p>An unknown tag is refused ({@link IllegalArgumentException}) rather than
 * replaced by a default: a mistyped staging tag must not turn into a server
 * that writes live data.</p>
 */
public record ServerIdentity(String id, String tag, boolean configured) {
    public static final Set<String> TAGS = Set.of("hub", "backend", "staging", "live");

    /** An identity whose tag was set explicitly. */
    public ServerIdentity(String id, String tag) {
        this(id, tag, true);
    }

    /** The built-in default ({@code live}/{@code live}, tag not configured): the legacy single server. */
    public static ServerIdentity defaults() {
        return new ServerIdentity("live", "live", false);
    }

    public static ServerIdentity from(@Nullable ConfigurationSection server) {
        return from(server, System.getenv());
    }

    /** As {@link #from(ConfigurationSection)} with the environment given (production passes {@code System.getenv()}). */
    public static ServerIdentity from(@Nullable ConfigurationSection server, Map<String, String> env) {
        String id = pick(env.get("WOS_SERVER_ID"), server == null ? null : server.getString("id"));
        String envTag = env.get("WOS_SERVER_TAG");
        String yamlTag = server == null ? null : server.getString("tag");
        String tag = pick(envTag, yamlTag);
        if (!TAGS.contains(tag)) {
            throw new IllegalArgumentException("Unknown server tag '" + tag + "' (expected hub, backend, staging or live)");
        }
        return new ServerIdentity(id, tag, isSet(envTag) || isSet(yamlTag));
    }

    private static boolean isSet(@Nullable String s) {
        return s != null && !s.isBlank();
    }

    private static String pick(@Nullable String env, @Nullable String yaml) {
        if (isSet(env)) return env.trim();
        if (isSet(yaml)) return yaml.trim();
        return "live";
    }

    public boolean isLive() { return tag.equals("live"); }

    public boolean isHub() { return tag.equals("hub"); }

    /** {@code live} for hub and live servers, {@code test} for backend and staging. */
    public String realm() { return tag.equals("hub") || tag.equals("live") ? "live" : "test"; }

    /**
     * Only a configured {@code live} server ignores content reloads (it loads content at
     * start); every other server, and the unconfigured legacy single server, takes them.
     */
    public boolean takesContentReloads() { return !(configured && isLive()); }
}
