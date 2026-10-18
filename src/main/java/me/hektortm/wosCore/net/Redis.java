package me.hektortm.wosCore.net;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * The small slice of Redis the {@link NetworkBus} needs, so the bus's codec and
 * request / timeout logic can be tested with a fake (no Redis server in tests).
 *
 * <p>Implementations must never throw out of {@link #publish} or {@link #subscribe}
 * for a lost connection: a dropped Redis must not crash or block the server.</p>
 */
interface Redis extends AutoCloseable {

    /** Sends {@code message} to {@code channel}. May block briefly; the bus calls it off the main thread. */
    void publish(String channel, String message);

    /**
     * Starts listening on {@code channel}. The future completes once Redis has confirmed the
     * subscription (so a request can safely publish after it); if Redis is down it completes
     * after the next successful reconnect.
     */
    CompletableFuture<Void> subscribe(String channel);

    void unsubscribe(String channel);

    /** Sets the receiver of every message on a subscribed channel: (channel, raw message). Called on the subscriber thread. */
    void onMessage(BiConsumer<String, String> listener);

    @Override
    void close();
}
