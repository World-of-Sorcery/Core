package me.hektortm.wosCore.net;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkBusTest {

    /** An in-memory "Redis": every node sees what any node publishes to a channel it subscribed to. */
    static final class FakeHub {
        final List<FakeRedis> nodes = new ArrayList<>();
        final List<String[]> published = new ArrayList<>();

        FakeRedis node() {
            FakeRedis r = new FakeRedis(this);
            nodes.add(r);
            return r;
        }
    }

    static final class FakeRedis implements Redis {
        final FakeHub hub;
        final Set<String> subscribed = new HashSet<>();
        BiConsumer<String, String> listener = (c, m) -> { };
        boolean closed;

        FakeRedis(FakeHub hub) {
            this.hub = hub;
        }

        @Override
        public void publish(String channel, String message) {
            hub.published.add(new String[]{channel, message});
            for (FakeRedis n : new ArrayList<>(hub.nodes)) {
                if (n.subscribed.contains(channel)) n.listener.accept(channel, message);
            }
        }

        @Override
        public CompletableFuture<Void> subscribe(String channel) {
            subscribed.add(channel);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void unsubscribe(String channel) {
            subscribed.remove(channel);
        }

        @Override
        public void onMessage(BiConsumer<String, String> listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static NetworkBus bus(FakeRedis redis, String id) {
        return new NetworkBus(redis, id, Runnable::run, Runnable::run, List.of(), Logger.getLogger("test"));
    }

    private static JsonObject obj(String k, String v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }

    @Test
    void envelopeRoundTrip() {
        Envelope in = new Envelope("lobby-1", "wos:chat", obj("text", "hi \"there\""), "abc");
        Envelope out = Envelope.parse(in.toJson());
        assertThat(out).isEqualTo(in);

        Envelope noReply = Envelope.parse(new Envelope("a", "t", new JsonObject(), null).toJson());
        assertThat(noReply.replyTo()).isNull();
        assertThat(new Envelope("a", "t", new JsonObject(), null).toJson()).contains("\"reply_to\":null");
    }

    @Test
    void badEnvelopeIsRefused() {
        assertThatThrownBy(() -> Envelope.parse("not json {")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Envelope.parse("[]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Envelope.parse("{\"body\":{}}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disabledBusDoesNothing() {
        NetworkBus bus = NetworkBus.disabled();
        List<Envelope> got = new ArrayList<>();
        bus.publish("wos:chat", obj("a", "b"));
        bus.subscribe("wos:chat", got::add);
        bus.onRequest("ping", b -> CompletableFuture.completedFuture(b));
        assertThat(bus.isEnabled()).isFalse();
        assertThat(got).isEmpty();
        bus.close();
    }

    @Test
    void disabledRequestFailsFast() {
        CompletableFuture<JsonObject> f = NetworkBus.disabled().request("other", "ping", new JsonObject(), Duration.ofSeconds(30));
        assertThat(f).isCompletedExceptionally();
        assertThatThrownBy(f::get).isInstanceOf(ExecutionException.class).hasMessageContaining("disabled");
    }

    @Test
    void publishSetsFromAndSubscribersGetIt() {
        FakeHub hub = new FakeHub();
        NetworkBus a = bus(hub.node(), "lobby-1");
        NetworkBus b = bus(hub.node(), "lobby-2");
        List<Envelope> got = new ArrayList<>();
        b.subscribe("wos:chat", got::add);
        a.publish("wos:chat", obj("text", "hello"));
        assertThat(got).hasSize(1);
        assertThat(got.get(0).from()).isEqualTo("lobby-1");
        assertThat(got.get(0).body().get("text").getAsString()).isEqualTo("hello");
        assertThat(got.get(0).replyTo()).isNull();
    }

    @Test
    void requestGetsTheAnswerOfTheOtherServer() throws Exception {
        FakeHub hub = new FakeHub();
        FakeRedis ra = hub.node();
        NetworkBus a = bus(ra, "lobby-1");
        NetworkBus b = bus(hub.node(), "survival-1");
        b.onRequest("handoff", body -> CompletableFuture.completedFuture(obj("ok", body.get("player").getAsString())));

        JsonObject answer = a.request("survival-1", "handoff", obj("player", "Steve"), Duration.ofSeconds(2)).get(2, TimeUnit.SECONDS);

        assertThat(answer.get("ok").getAsString()).isEqualTo("Steve");
        assertThat(ra.subscribed).noneMatch(c -> c.startsWith("wos:reply:")); // reply channel cleaned up
        assertThat(hub.published.get(0)[0]).isEqualTo("wos:req:survival-1");
        assertThat(hub.published.get(0)[1]).doesNotContain("\"reply_to\":null");
    }

    @Test
    void requestWithoutHandlerOrWithFailingHandlerFails() {
        FakeHub hub = new FakeHub();
        NetworkBus a = bus(hub.node(), "lobby-1");
        NetworkBus b = bus(hub.node(), "survival-1");
        b.onRequest("boom", body -> CompletableFuture.failedFuture(new IllegalStateException("nope")));

        assertThatThrownBy(() -> a.request("survival-1", "boom", new JsonObject(), Duration.ofSeconds(2)).get())
                .isInstanceOf(ExecutionException.class).hasMessageContaining("nope");
        assertThatThrownBy(() -> a.request("survival-1", "unknown", new JsonObject(), Duration.ofSeconds(2)).get())
                .isInstanceOf(ExecutionException.class).hasMessageContaining("no handler");
    }

    @Test
    void requestTimesOutWhenNobodyAnswers() {
        FakeHub hub = new FakeHub();
        FakeRedis ra = hub.node();
        NetworkBus a = bus(ra, "lobby-1");

        CompletableFuture<JsonObject> f = a.request("ghost", "ping", new JsonObject(), Duration.ofMillis(50));

        assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(TimeoutException.class);
        assertThat(ra.subscribed).noneMatch(c -> c.startsWith("wos:reply:"));
    }

    @Test
    void urlComesFromEnvThenYaml() {
        assertThat(NetworkBus.resolveUrl("redis://env", "redis://yaml")).isEqualTo("redis://env");
        assertThat(NetworkBus.resolveUrl("  ", "redis://yaml")).isEqualTo("redis://yaml");
        assertThat(NetworkBus.resolveUrl(null, null)).isEmpty();
        assertThat(NetworkBus.create("", "x", Logger.getLogger("test")).isEnabled()).isFalse();
    }

    @Test
    void closeClosesRedis() {
        FakeRedis r = new FakeHub().node();
        bus(r, "x").close();
        assertThat(r.closed).isTrue();
    }

    @Test
    void aSlowHandlerDoesNotDelayARepliesFuture() throws Exception {
        FakeHub hub = new FakeHub();
        ExecutorService slowDispatcher = Executors.newSingleThreadExecutor();
        try {
            NetworkBus a = new NetworkBus(hub.node(), "lobby-1", Runnable::run, slowDispatcher, List.of(), Logger.getLogger("test"));
            NetworkBus b = bus(hub.node(), "survival-1");
            b.onRequest("ping", body -> CompletableFuture.completedFuture(obj("pong", "1")));
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            a.subscribe("wos:chat", env -> {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            b.publish("wos:chat", new JsonObject());
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); // the dispatcher thread is now stuck

            JsonObject answer = a.request("survival-1", "ping", new JsonObject(), Duration.ofSeconds(2)).get(2, TimeUnit.SECONDS);

            assertThat(answer.get("pong").getAsString()).isEqualTo("1");
            release.countDown();
        } finally {
            slowDispatcher.shutdownNow();
        }
    }

    @Test
    void publishAfterCloseIsDroppedSilently() {
        FakeHub hub = new FakeHub();
        ExecutorService io = Executors.newSingleThreadExecutor();
        NetworkBus a = new NetworkBus(hub.node(), "lobby-1", io, Runnable::run, List.of(io), Logger.getLogger("test"));
        a.close();
        a.publish("wos:chat", new JsonObject()); // must not throw
        assertThat(hub.published).isEmpty();
    }

    @Test
    void queuedMessagesAreSentBeforeRedisCloses() {
        FakeHub hub = new FakeHub();
        ExecutorService io = Executors.newSingleThreadExecutor();
        NetworkBus a = new NetworkBus(hub.node(), "lobby-1", io, Runnable::run, List.of(io), Logger.getLogger("test"));
        for (int i = 0; i < 20; i++) a.publish("wos:staff", new JsonObject());
        a.close();
        assertThat(hub.published).hasSize(20);
    }

    @Test
    void malformedUrlNeverLeaksItsPassword() {
        String url = "redis://:s3cretPass@bad host:6379";
        assertThatThrownBy(() -> new JedisRedis(url, Logger.getLogger("test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("s3cretPass")
                .hasNoCause();

        Logger log = Logger.getLogger("wos-test-url");
        log.setUseParentHandlers(false);
        List<String> logged = new ArrayList<>();
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { logged.add(r.getMessage() + " " + java.util.Arrays.toString(r.getParameters())); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        NetworkBus bus = NetworkBus.create(url, "x", log);
        assertThat(bus.isEnabled()).isFalse();
        assertThat(logged).isNotEmpty().noneMatch(l -> l.contains("s3cretPass"));
    }

    @Test
    void urlWithPasswordIsAcceptedAndNeverLogged() {
        // The deploy's form (redis://:<password>@redis:6379); nothing listens on port 1, so the subscriber just retries.
        String url = "redis://:s3cretPass@127.0.0.1:1";
        Logger log = Logger.getLogger("wos-test-url-ok");
        log.setUseParentHandlers(false);
        List<String> logged = java.util.Collections.synchronizedList(new ArrayList<>());
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord r) {
                logged.add(r.getMessage() + " " + java.util.Arrays.toString(r.getParameters())
                        + (r.getThrown() == null ? "" : " " + r.getThrown()));
            }
            @Override public void flush() { }
            @Override public void close() { }
        });
        NetworkBus bus = NetworkBus.create(url, "x", log);
        try {
            assertThat(bus.isEnabled()).isTrue();
            bus.publish("wos:chat", new JsonObject()); // fails to connect: logs a warning without the URL
        } finally {
            bus.close();
        }
        assertThat(logged).isNotEmpty();
        synchronized (logged) {
            assertThat(logged).noneMatch(l -> l.contains("s3cretPass"));
        }
    }
}
