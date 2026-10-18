package me.hektortm.wosCore.presence;

import com.sun.net.httpserver.HttpServer;
import me.hektortm.wosCore.api.ApiException;
import me.hektortm.wosCore.api.WosApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The whole network's online list from wos-api. */
class NetworkOnlineTest {
    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private NetworkOnline answering(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        return new NetworkOnline(new WosApi("http://127.0.0.1:" + server.getAddress().getPort(), "t",
                Duration.ofSeconds(2), Logger.getLogger("NetworkOnlineTest")));
    }

    @Test
    void readsEveryServersPlayers() throws Exception {
        NetworkOnline online = answering(200, "[{\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\",\"username\":\"Alex\",\"server\":\"hub\"},"
                + "{\"uuid\":\"28c63f65-52cc-47f0-8246-b16f2176da23\",\"username\":\"Sam\",\"server\":\"live\"}]");

        assertThat(online.fetch()).containsExactly(
                new NetworkPlayer(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Alex", "hub"),
                new NetworkPlayer(UUID.fromString("28c63f65-52cc-47f0-8246-b16f2176da23"), "Sam", "live"));
        assertThat(path.get()).isEqualTo("GET /v1/server/online");
    }

    @Test
    void skipsBadRows() throws Exception {
        NetworkOnline online = answering(200, "[{\"uuid\":\"nope\",\"username\":\"X\",\"server\":\"hub\"},{\"username\":\"Y\"},"
                + "{\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\",\"username\":\"Alex\",\"server\":\"hub\"}]");

        assertThat(online.fetch()).extracting(NetworkPlayer::username).containsExactly("Alex");
    }

    @Test
    void callersWhileAFetchRunsShareIt() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Instant> received = new java.util.concurrent.atomic.AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            received.set(Instant.now());
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
            exchange.close();
        });
        server.start();
        NetworkOnline online = new NetworkOnline(new WosApi("http://127.0.0.1:" + server.getAddress().getPort(), "t",
                Duration.ofSeconds(2), Logger.getLogger("NetworkOnlineTest")));

        java.util.concurrent.CompletableFuture<NetworkOnline.Snapshot> first = online.fetchSnapshot();
        java.util.concurrent.CompletableFuture<NetworkOnline.Snapshot> second = online.fetchSnapshot();
        java.util.concurrent.CompletableFuture<java.util.List<NetworkPlayer>> third = online.fetchAsync();
        assertThat(second).isSameAs(first);
        release.countDown();
        NetworkOnline.Snapshot snap = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(third.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEmpty();
        assertThat(requests.get()).isEqualTo(1);
        // askedAt is taken before the request goes out: never after the API saw it.
        assertThat(snap.askedAt()).isBeforeOrEqualTo(received.get());

        // Once done, the next caller asks again (no stale answer is handed out).
        online.fetchSnapshot().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(requests.get()).isEqualTo(2);
        online.close();
    }

    @Test
    void anErrorIsAnApiException() throws Exception {
        NetworkOnline online = answering(403, "{\"error\":{\"code\":\"forbidden\",\"message\":\"no\"}}");

        assertThatThrownBy(online::fetch).isInstanceOf(ApiException.class);
    }
}
