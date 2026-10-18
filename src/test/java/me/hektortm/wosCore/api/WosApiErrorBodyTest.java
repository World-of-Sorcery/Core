package me.hektortm.wosCore.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** A failed call keeps the parsed JSON body of the API's answer. */
class WosApiErrorBodyTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private WosApi answering(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
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
        return new WosApi("http://127.0.0.1:" + server.getAddress().getPort(), "t", Duration.ofSeconds(2),
                Logger.getLogger("WosApiErrorBodyTest"));
    }

    @Test
    void conflictBodyIsKept() throws IOException {
        WosApi api = answering(409, "{\"held_by\":\"hub-1\",\"error\":{\"code\":\"lease_held\","
                + "\"message\":\"the player is held by server hub-1\"}}");
        ApiException e = catchThrowableOfType(() -> api.send("POST", "/v1/players/x/lease", null), ApiException.class);
        assertThat(e.status()).isEqualTo(409);
        assertThat(e.code()).isEqualTo("lease_held");
        assertThat(e.body()).isPresent();
        assertThat(e.field("held_by")).contains("hub-1");
        assertThat(e.field("missing")).isEmpty();
        assertThat(e.field("error")).isEmpty(); // not a string
    }

    @Test
    void bodyThatIsNotJsonIsEmpty() throws IOException {
        WosApi api = answering(409, "<html>conflict</html>");
        ApiException e = catchThrowableOfType(() -> api.send("POST", "/v1/x", null), ApiException.class);
        assertThat(e.code()).isEqualTo("http_409");
        assertThat(e.body()).isEmpty();
        assertThat(e.field("held_by")).isEmpty();
    }

    @Test
    void oldConstructorsHaveNoBody() {
        assertThat(new ApiException(409, "lease_held", "x").body()).isEmpty();
        assertThat(new ApiException("down", new IOException("x")).field("held_by")).isEmpty();
    }
}
