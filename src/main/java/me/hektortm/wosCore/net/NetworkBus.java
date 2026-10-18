package me.hektortm.wosCore.net;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Message bus between game servers over Redis pub/sub (chat, the server-switch hand-off,
 * staff alerts). Channels: {@code wos:chat}, {@code wos:msg}, {@code wos:staff},
 * {@code wos:friends}, {@code wos:req:<serverId>}, {@code wos:reply:<id>}.
 *
 * <p><b>Threading:</b> {@link #publish} never blocks the caller (the Redis write happens on a
 * background thread). Subscribers and request handlers are called <b>off the main server
 * thread</b>, one message at a time in arrival order, on a single dispatcher thread: hop to
 * the main thread (the scheduler) before touching Bukkit, and do not block in a handler.</p>
 *
 * <p>Replies to a {@link #request} complete their future directly on the receiving (Redis) thread, not
 * behind other handlers. Still, <b>never {@code join()} / {@code get()} a request inside a handler</b>:
 * it would hold the dispatcher thread while waiting for a reply that needs this server to keep working.
 * Chain with {@code thenAccept} instead.</p>
 *
 * <p>With no Redis configured use {@link #disabled()}: publish and subscribe do nothing and
 * {@link #request} fails at once, so callers can fall back immediately.</p>
 */
public final class NetworkBus implements AutoCloseable {
    public static final String REQ_PREFIX = "wos:req:";
    public static final String REPLY_PREFIX = "wos:reply:";
    private static final String ERROR_TYPE = "error";

    private final @Nullable Redis redis;
    private final String serverId;
    private final Executor io;
    private final Executor dispatch;
    private final List<ExecutorService> owned;
    private final Logger log;

    private final Map<String, List<Consumer<Envelope>>> handlers = new ConcurrentHashMap<>();
    private final Map<String, Function<JsonObject, CompletableFuture<JsonObject>>> requestHandlers = new ConcurrentHashMap<>();
    private final AtomicBoolean listeningForRequests = new AtomicBoolean();

    NetworkBus(@Nullable Redis redis, String serverId, Executor io, Executor dispatch, List<ExecutorService> owned, Logger log) {
        this.redis = redis;
        this.serverId = serverId;
        this.io = io;
        this.dispatch = dispatch;
        this.owned = owned;
        this.log = log;
        if (redis != null) redis.onMessage(this::onRaw);
    }

    /** A bus that does nothing: publish and subscribe are no-ops, {@link #request} fails at once. */
    public static NetworkBus disabled() {
        return new NetworkBus(null, "", Runnable::run, Runnable::run, List.of(), Logger.getLogger("WoSCore"));
    }

    /**
     * The bus for {@code url} (a {@code redis://} or {@code rediss://} URL), or {@link #disabled()} when it
     * is blank. Never logs the URL (it may hold a password). A bad URL logs a warning and disables the bus.
     */
    public static NetworkBus create(@Nullable String url, String serverId, Logger log) {
        if (url == null || url.isBlank()) {
            log.info("Redis bus disabled");
            return disabled();
        }
        try {
            Redis redis = new JedisRedis(url.trim(), log);
            ExecutorService io = Executors.newSingleThreadExecutor(daemon("WoS-Bus-Publish"));
            ExecutorService dispatch = Executors.newSingleThreadExecutor(daemon("WoS-Bus-Dispatch"));
            NetworkBus bus = new NetworkBus(redis, serverId, io, dispatch, List.of(io, dispatch), log);
            log.info("Redis bus enabled");
            return bus;
        } catch (RuntimeException e) {
            // Fixed text on purpose: an exception message could hold the URL and its password.
            log.warning("Redis bus disabled: redis.url is not valid, or the Redis client could not be set up");
            return disabled();
        }
    }

    /** The URL to use: environment {@code WOS_REDIS_URL} wins over yaml {@code redis.url}; blank means none. */
    public static String resolveUrl(@Nullable String env, @Nullable String yaml) {
        if (env != null && !env.isBlank()) return env.trim();
        return yaml == null ? "" : yaml.trim();
    }

    public boolean isEnabled() {
        return redis != null;
    }

    /** Publishes {@code body} on {@code channel}; the envelope's type is the channel name. */
    public void publish(String channel, JsonObject body) {
        publish(channel, channel, body);
    }

    /** Publishes {@code body} on {@code channel} as message {@code type}, from this server. */
    public void publish(String channel, String type, JsonObject body) {
        send(channel, new Envelope(serverId, type, body, null));
    }

    /** Calls {@code h} (off the main thread) for every message on {@code channel}, including this server's own. */
    public void subscribe(String channel, Consumer<Envelope> h) {
        if (redis == null) return;
        handlers.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(h);
        redis.subscribe(channel);
    }

    /**
     * Asks server {@code toServer} and completes with its answer. Completes exceptionally when the bus is
     * disabled (at once), when the other server answers with an error, or after {@code timeout}.
     *
     * <p>The future completes on the Redis subscriber thread, so continuations ({@code thenApply},
     * {@code thenAccept}, ...) run there: hop to your own executor (or the main thread's scheduler)
     * for anything slow, or the whole bus stalls.</p>
     */
    public CompletableFuture<JsonObject> request(String toServer, String type, JsonObject body, Duration timeout) {
        if (redis == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Redis bus is disabled"));
        }
        String id = UUID.randomUUID().toString();
        String replyChannel = REPLY_PREFIX + id;
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        handlers.computeIfAbsent(replyChannel, c -> new CopyOnWriteArrayList<>()).add(env -> {
            if (ERROR_TYPE.equals(env.type())) {
                result.completeExceptionally(new IllegalStateException("Request '" + type + "' to " + toServer + " failed: "
                        + (env.body().has("message") ? env.body().get("message").getAsString() : "unknown error")));
            } else {
                result.complete(env.body());
            }
        });
        result.orTimeout(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        result.whenComplete((r, e) -> {
            handlers.remove(replyChannel);
            redis.unsubscribe(replyChannel);
        });
        // Publish only once Redis confirmed the reply subscription, or the answer could be missed.
        redis.subscribe(replyChannel).whenComplete((v, e) -> {
            if (e != null) result.completeExceptionally(e);
            else send(REQ_PREFIX + toServer, new Envelope(serverId, type, body, id));
        });
        return result;
    }

    /** Answers requests of {@code type} sent to this server. The handler runs off the main thread. */
    public void onRequest(String type, Function<JsonObject, CompletableFuture<JsonObject>> handler) {
        if (redis == null) return;
        requestHandlers.put(type, handler);
        if (listeningForRequests.compareAndSet(false, true)) {
            subscribe(REQ_PREFIX + serverId, this::answer);
        }
    }

    private void answer(Envelope request) {
        String replyTo = request.replyTo();
        if (replyTo == null) return;
        Function<JsonObject, CompletableFuture<JsonObject>> h = requestHandlers.get(request.type());
        if (h == null) {
            reply(replyTo, ERROR_TYPE, error("no handler for '" + request.type() + "'"));
            return;
        }
        CompletableFuture<JsonObject> out;
        try {
            out = h.apply(request.body());
        } catch (RuntimeException e) {
            reply(replyTo, ERROR_TYPE, error(String.valueOf(e.getMessage())));
            return;
        }
        if (out == null) {
            reply(replyTo, request.type(), new JsonObject());
            return;
        }
        out.whenComplete((r, e) -> {
            if (e != null) reply(replyTo, ERROR_TYPE, error(String.valueOf(e.getMessage())));
            else reply(replyTo, request.type(), r == null ? new JsonObject() : r);
        });
    }

    private void reply(String replyTo, String type, JsonObject body) {
        send(REPLY_PREFIX + replyTo, new Envelope(serverId, type, body, null));
    }

    private static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("message", message);
        return o;
    }

    private void send(String channel, Envelope env) {
        if (redis == null) return;
        String json = env.toJson();
        try {
            io.execute(() -> redis.publish(channel, json));
        } catch (RejectedExecutionException e) {
            // After close(): drop the message, publish never throws.
        }
    }

    private void onRaw(String channel, String message) {
        List<Consumer<Envelope>> hs = handlers.get(channel);
        if (hs == null) return;
        Envelope env;
        try {
            env = Envelope.parse(message);
        } catch (IllegalArgumentException e) {
            log.fine("Ignoring a bad bus message on " + channel + ": " + e.getMessage());
            return;
        }
        // Replies complete a future and nothing else: do it right here, so a slow handler on the
        // dispatcher thread cannot delay a request's answer.
        Executor target = channel.startsWith(REPLY_PREFIX) ? Runnable::run : dispatch;
        for (Consumer<Envelope> h : hs) {
            try {
                target.execute(() -> {
                    try {
                        h.accept(env);
                    } catch (RuntimeException e) {
                        log.warning("Bus handler for " + channel + " failed: " + e);
                    }
                });
            } catch (RejectedExecutionException e) {
                return; // closed
            }
        }
    }

    /** Stops the subscriber thread, the executors and the Redis pool. Safe to call twice. */
    @Override
    public void close() {
        // Let messages queued just before shutdown (other plugins' onDisable) go out, then close Redis.
        for (ExecutorService e : owned) {
            e.shutdown();
            try {
                if (!e.awaitTermination(2, TimeUnit.SECONDS)) e.shutdownNow();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                e.shutdownNow();
            }
        }
        if (redis != null) redis.close();
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
