package me.hektortm.wosCore.net;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against a real Redis, only when {@code WOS_REDIS_TEST_URL} is set, e.g.
 * {@code docker run --rm -d --name wos-redis-test -p 6390:6379 redis:7-alpine} and
 * {@code WOS_REDIS_TEST_URL=redis://localhost:6390}. The normal offline build skips it.
 */
@EnabledIfEnvironmentVariable(named = "WOS_REDIS_TEST_URL", matches = ".+")
class JedisRedisLiveTest {
    private static final Logger LOG = Logger.getLogger("live-test");

    private static String url() {
        return System.getenv("WOS_REDIS_TEST_URL");
    }

    @Test
    void publishSubscribeRequestAnswerAndClose() throws Exception {
        NetworkBus a = NetworkBus.create(url(), "live-a", LOG);
        NetworkBus b = NetworkBus.create(url(), "live-b", LOG);
        assertThat(a.isEnabled()).isTrue();
        try {
            // subscribe / publish: the subscription is asynchronous, so publish until it arrives
            CountDownLatch got = new CountDownLatch(1);
            String[] from = new String[1];
            b.subscribe("wos:chat", env -> {
                from[0] = env.from() + ":" + env.body().get("text").getAsString();
                got.countDown();
            });
            JsonObject msg = new JsonObject();
            msg.addProperty("text", "hello");
            for (int i = 0; i < 50 && got.getCount() > 0; i++) {
                a.publish("wos:chat", msg);
                got.await(100, TimeUnit.MILLISECONDS);
            }
            assertThat(got.getCount()).isZero();
            assertThat(from[0]).isEqualTo("live-a:hello");

            // request / answer between the two buses
            b.onRequest("ping", body -> {
                JsonObject r = new JsonObject();
                r.addProperty("echo", body.get("n").getAsString());
                return java.util.concurrent.CompletableFuture.completedFuture(r);
            });
            JsonObject body = new JsonObject();
            body.addProperty("n", "42");
            JsonObject answer = null;
            for (int i = 0; i < 10 && answer == null; i++) { // b's request channel may not be confirmed yet
                try {
                    answer = a.request("live-b", "ping", body, Duration.ofSeconds(1)).get(3, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    // timed out: try again
                }
            }
            assertThat(answer).isNotNull();
            assertThat(answer.get("echo").getAsString()).isEqualTo("42");
        } finally {
            long t = System.nanoTime();
            a.close();
            b.close();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t)).isLessThan(5_000);
        }
        // close() ends the subscriber threads
        long deadline = System.currentTimeMillis() + 3_000;
        while (subscriberThreads() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertThat(subscriberThreads()).isZero();
    }

    /**
     * Needs a second variable, {@code WOS_REDIS_PAUSE_CONTAINER=wos-redis-test} (the throwaway container
     * behind the URL): freezes Redis with {@code docker pause} for 55 s, so the subscriber's 45 s read
     * timeout must fire, and then checks the bus reconnects and receives again.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "WOS_REDIS_PAUSE_CONTAINER", matches = ".+")
    void aFrozenRedisIsNoticedAndTheBusReconnects() throws Exception {
        String container = System.getenv("WOS_REDIS_PAUSE_CONTAINER");
        Logger log = Logger.getLogger("live-pause-test");
        log.setUseParentHandlers(false);
        java.util.List<String> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
        log.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) {
                logged.add(java.text.MessageFormat.format(String.valueOf(r.getMessage()), r.getParameters() == null ? new Object[0] : r.getParameters()));
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
        NetworkBus sender = NetworkBus.create(url(), "pause-a", LOG);
        NetworkBus receiver = NetworkBus.create(url(), "pause-b", log);
        try {
            CountDownLatch first = new CountDownLatch(1);
            CountDownLatch afterPause = new CountDownLatch(1);
            receiver.subscribe("wos:staff", env -> {
                String m = env.body().get("n").getAsString();
                if (m.equals("before")) first.countDown();
                if (m.equals("after")) afterPause.countDown();
            });
            for (int i = 0; i < 50 && first.getCount() > 0; i++) {
                sender.publish("wos:staff", n("before"));
                first.await(100, TimeUnit.MILLISECONDS);
            }
            assertThat(first.getCount()).isZero();

            docker("pause", container);
            try {
                long end = System.currentTimeMillis() + 55_000;
                while (System.currentTimeMillis() < end && logged.stream().noneMatch(l -> l.contains("lost the connection"))) {
                    Thread.sleep(500);
                }
            } finally {
                docker("unpause", container);
            }
            assertThat(logged).anyMatch(l -> l.contains("lost the connection"));

            for (int i = 0; i < 100 && afterPause.getCount() > 0; i++) { // backoff 1 s, then resubscribe
                sender.publish("wos:staff", n("after"));
                afterPause.await(200, TimeUnit.MILLISECONDS);
            }
            assertThat(afterPause.getCount()).isZero();
        } finally {
            sender.close();
            receiver.close();
        }
    }

    private static JsonObject n(String v) {
        JsonObject o = new JsonObject();
        o.addProperty("n", v);
        return o;
    }

    private static void docker(String verb, String container) throws Exception {
        Process p = new ProcessBuilder("docker", verb, container).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();
    }

    private static long subscriberThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && "WoS-Redis-Subscriber".equals(t.getName())).count();
    }
}
