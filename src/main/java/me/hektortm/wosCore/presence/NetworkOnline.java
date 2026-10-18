package me.hektortm.wosCore.presence;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.hektortm.wosCore.api.ApiException;
import me.hektortm.wosCore.api.WosApi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Who is online on the whole network ({@code GET /v1/server/online}): every server and realm,
 * with the server each player is on. Every server reports its own players
 * ({@link PresenceReporter}); wos-api leaves out lists not refreshed for 90 s.
 *
 * <p>{@link #fetch} blocks: never call it on the main server thread. {@link #fetchSnapshot} and
 * {@link #fetchAsync} ask on a background thread, one request at a time: a caller that comes while a
 * request is running gets that request's answer (so a hanging wos-api never builds a queue). Nothing
 * is cached after it: the next caller asks again. Continuations run on the background thread, so hop
 * to the main thread before touching Bukkit.</p>
 */
public final class NetworkOnline {
    static final String PATH = "/v1/server/online";

    /**
     * An answer and when it was asked for.
     *
     * @param askedAt taken just before the request went out: the list is at least this fresh
     */
    public record Snapshot(List<NetworkPlayer> players, Instant askedAt) {}

    private final WosApi api;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WoS-NetworkOnline");
        t.setDaemon(true);
        return t;
    });
    private CompletableFuture<Snapshot> inFlight;

    public NetworkOnline(WosApi api) {
        this.api = api;
    }

    /** Everyone online on the network, in one request (no retries). Blocking; rows without a valid uuid are left out. */
    public List<NetworkPlayer> fetch() throws ApiException {
        JsonElement json = api.getJsonOnce(PATH);
        List<NetworkPlayer> out = new ArrayList<>();
        if (json == null || !json.isJsonArray()) return out;
        for (JsonElement e : json.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            try {
                out.add(new NetworkPlayer(UUID.fromString(o.get("uuid").getAsString()),
                        o.get("username").getAsString(), o.get("server").getAsString()));
            } catch (RuntimeException bad) {
                // a row we can't read: skip it
            }
        }
        return out;
    }

    /**
     * The list with the time it was asked for, from a background thread; the running request's future when
     * one is running. Fails with the {@link ApiException} when wos-api can't answer.
     */
    public synchronized CompletableFuture<Snapshot> fetchSnapshot() {
        if (inFlight != null && !inFlight.isDone()) return inFlight;
        CompletableFuture<Snapshot> result = new CompletableFuture<>();
        inFlight = result;
        try {
            worker.execute(() -> {
                try {
                    Instant askedAt = Instant.now();
                    result.complete(new Snapshot(fetch(), askedAt));
                } catch (ApiException | RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopping) {
            result.completeExceptionally(stopping);
        }
        return result;
    }

    /** {@link #fetchSnapshot} without the time. */
    public CompletableFuture<List<NetworkPlayer>> fetchAsync() {
        return fetchSnapshot().thenApply(Snapshot::players);
    }

    /** Stops the background thread (at disable). */
    public void close() {
        worker.shutdownNow();
    }
}
