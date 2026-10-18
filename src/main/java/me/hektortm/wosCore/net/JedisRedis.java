package me.hektortm.wosCore.net;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.util.JedisURIHelper;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link Redis} on Jedis. Publishing uses a small pool; subscribing runs on one dedicated
 * daemon thread (a Jedis subscribe blocks) that reconnects with a backoff of 1 s doubling
 * to at most 30 s and re-subscribes every channel.
 *
 * <p>Jedis does not synchronise writes on the pub/sub connection, so every SUBSCRIBE,
 * UNSUBSCRIBE and PING goes through {@code commandLock}. A PING goes out every 30 s and the
 * subscriber socket has a 45 s read timeout, so a half-open connection counts as lost within
 * about a minute and takes the reconnect path.</p>
 *
 * <p>The URL may hold a password: it is never logged and never put in an exception message.</p>
 */
final class JedisRedis implements Redis {
    private static final long BACKOFF_MIN_MS = 1_000;
    private static final long BACKOFF_MAX_MS = 30_000;
    private static final long PING_SECONDS = 30;
    private static final int SUBSCRIBER_READ_TIMEOUT_MS = 45_000;

    private final Logger log;
    private final HostAndPort address;
    private final JedisClientConfig subscriberConfig;
    private final JedisPool pool;

    private final Set<String> channels = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> confirmations = new ConcurrentHashMap<>();
    private final Object wake = new Object();
    /** Serialises every write on the shared pub/sub connection. */
    private final Object commandLock = new Object();
    private final Thread thread;
    private final ScheduledExecutorService pinger;
    private volatile BiConsumer<String, String> listener = (c, m) -> { };
    private volatile JedisPubSub active;
    private volatile Jedis connection;
    private volatile boolean closed;
    private volatile long lastPublishWarn;

    JedisRedis(String url, Logger log) {
        this.log = log;
        HostAndPort hp;
        String user;
        String password;
        int db;
        boolean ssl;
        try {
            URI uri = URI.create(url);
            if (!JedisURIHelper.isValid(uri)) throw new IllegalArgumentException();
            hp = JedisURIHelper.getHostAndPort(uri);
            user = JedisURIHelper.getUser(uri);
            password = JedisURIHelper.getPassword(uri);
            db = JedisURIHelper.getDBIndex(uri);
            ssl = JedisURIHelper.isRedisSSLScheme(uri);
        } catch (RuntimeException e) {
            // Deliberately without the cause: its message would hold the URL, password included.
            throw new IllegalArgumentException("redis.url is not a valid redis:// URL");
        }
        this.address = hp;
        this.subscriberConfig = DefaultJedisClientConfig.builder()
                .user(user).password(password).database(db).ssl(ssl)
                .connectionTimeoutMillis(5_000)
                .socketTimeoutMillis(SUBSCRIBER_READ_TIMEOUT_MS)
                // Jedis switches the socket to this "blocking" timeout once SUBSCRIBE starts
                // (Connection.setTimeoutInfinite); its default 0 would mean "wait forever".
                .blockingSocketTimeoutMillis(SUBSCRIBER_READ_TIMEOUT_MS)
                .build();
        JedisClientConfig publishConfig = DefaultJedisClientConfig.builder()
                .user(user).password(password).database(db).ssl(ssl)
                .connectionTimeoutMillis(2_000)
                .socketTimeoutMillis(2_000)
                .build();
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(4);
        poolConfig.setMaxWait(java.time.Duration.ofSeconds(2));
        this.pool = new JedisPool(poolConfig, address, publishConfig);
        this.pinger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WoS-Redis-Ping");
            t.setDaemon(true);
            return t;
        });
        this.pinger.scheduleWithFixedDelay(this::ping, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
        this.thread = new Thread(this::runSubscriber, "WoS-Redis-Subscriber");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    @Override
    public void publish(String channel, String message) {
        if (closed) return;
        try (Jedis j = pool.getResource()) {
            j.publish(channel, message);
        } catch (RuntimeException e) {
            // Rate-limited: a Redis outage must not flood the log.
            long now = System.currentTimeMillis();
            if (now - lastPublishWarn > BACKOFF_MAX_MS) {
                lastPublishWarn = now;
                log.warning("Redis publish failed (bus message dropped): " + e.getClass().getSimpleName());
            }
        }
    }

    @Override
    public CompletableFuture<Void> subscribe(String channel) {
        CompletableFuture<Void> confirmed = confirmations.computeIfAbsent(channel, c -> new CompletableFuture<>());
        channels.add(channel);
        sendSubscribe(channel);
        synchronized (wake) {
            wake.notifyAll();
        }
        return confirmed;
    }

    @Override
    public void unsubscribe(String channel) {
        channels.remove(channel);
        confirmations.remove(channel);
        synchronized (commandLock) {
            JedisPubSub p = active;
            if (p != null && p.isSubscribed()) {
                try {
                    p.unsubscribe(channel);
                } catch (RuntimeException ignored) {
                    // The connection broke just now; the channel is already out of the set.
                }
            }
        }
    }

    /** SUBSCRIBE on the live subscription, if there is one; otherwise the (re)connect picks the channel up. */
    private void sendSubscribe(String channel) {
        synchronized (commandLock) {
            JedisPubSub p = active;
            if (p != null && p.isSubscribed()) {
                try {
                    p.subscribe(channel);
                } catch (RuntimeException e) {
                    // The connection broke just now: the reconnect subscribes every channel again.
                }
            }
        }
    }

    private void ping() {
        synchronized (commandLock) {
            JedisPubSub p = active;
            if (p != null && p.isSubscribed()) {
                try {
                    p.ping();
                } catch (RuntimeException e) {
                    // The reader sees the broken connection and reconnects.
                }
            }
        }
    }

    @Override
    public void onMessage(BiConsumer<String, String> listener) {
        this.listener = listener;
    }

    private void runSubscriber() {
        long backoff = BACKOFF_MIN_MS;
        while (!closed) {
            try {
                synchronized (wake) {
                    while (channels.isEmpty() && !closed) wake.wait();
                }
                if (closed) break;
                try (Jedis j = new Jedis(address, subscriberConfig)) {
                    connection = j;
                    JedisPubSub pubSub = new Handler();
                    active = pubSub;
                    j.subscribe(pubSub, channels.toArray(new String[0]));
                }
                // Returned without error: the last channel was unsubscribed. Wait for the next one.
                backoff = BACKOFF_MIN_MS;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                if (closed) break;
                log.log(Level.WARNING, "Redis subscriber lost the connection ({0}); retrying in {1} s",
                        new Object[]{e.getClass().getSimpleName(), backoff / 1000});
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
            } finally {
                synchronized (commandLock) {
                    active = null;
                }
                connection = null;
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        pinger.shutdownNow();
        synchronized (commandLock) {
            JedisPubSub p = active;
            if (p != null) {
                try {
                    p.unsubscribe();
                } catch (RuntimeException ignored) {
                    // Closing anyway.
                }
            }
        }
        synchronized (wake) {
            wake.notifyAll();
        }
        thread.interrupt();
        try {
            thread.join(1_000);
            if (thread.isAlive()) {
                // Blocked in a socket read: closing the socket from here ends it.
                Jedis c = connection;
                if (c != null) {
                    try {
                        c.close();
                    } catch (RuntimeException ignored) {
                        // Closing anyway.
                    }
                }
                thread.join(2_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        confirmations.values().forEach(f -> f.completeExceptionally(new IllegalStateException("Redis bus closed")));
        try {
            pool.close();
        } catch (RuntimeException ignored) {
            // Closing anyway.
        }
    }

    /** Receives the subscription's callbacks on the subscriber thread. */
    private final class Handler extends JedisPubSub {
        @Override
        public void onSubscribe(String channel, int subscribedChannels) {
            CompletableFuture<Void> f = confirmations.get(channel);
            if (f != null) f.complete(null);
            // A channel added after the snapshot was taken but before this subscription went live.
            for (String c : channels) {
                if (!c.equals(channel) && !confirmations.getOrDefault(c, new CompletableFuture<>()).isDone()) {
                    sendSubscribe(c);
                }
            }
        }

        @Override
        public void onMessage(String channel, String message) {
            try {
                listener.accept(channel, message);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Redis bus listener failed", e);
            }
        }
    }
}
