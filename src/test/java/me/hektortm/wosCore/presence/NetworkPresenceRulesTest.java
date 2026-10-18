package me.hektortm.wosCore.presence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Join and leave lines only when a player enters or leaves the network, not on a server switch. */
class NetworkPresenceRulesTest {
    private static final UUID ALEX = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID SAM = UUID.fromString("28c63f65-52cc-47f0-8246-b16f2176da23");
    private static final Instant QUIT = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void firstServerIsAJoin() {
        assertThat(NetworkPresenceRules.isNetworkJoin(ALEX, "live", List.of())).isTrue();
        assertThat(NetworkPresenceRules.isNetworkJoin(ALEX, "live",
                List.of(new NetworkPlayer(SAM, "Sam", "hub")))).isTrue();
    }

    @Test
    void switchIsNotJoin() {
        // Velocity logs the player in on live before the hub sees them quit: the API still lists the hub.
        assertThat(NetworkPresenceRules.isNetworkJoin(ALEX, "live",
                List.of(new NetworkPlayer(ALEX, "Alex", "hub")))).isFalse();
    }

    @Test
    void ownServerInTheListIsStillAJoin() {
        // This server may already have reported the player itself.
        assertThat(NetworkPresenceRules.isNetworkJoin(ALEX, "live",
                List.of(new NetworkPlayer(ALEX, "Alex", "live")))).isTrue();
    }

    @Test
    void onlyAListAskedForBeforeTheJoinDecides() {
        Instant join = QUIT;
        List<NetworkPlayer> nobody = List.of();
        assertThat(NetworkPresenceRules.isNetworkJoin(ALEX, "live", new NetworkOnline.Snapshot(nobody, join.minusMillis(200)), join)).isTrue();
        // Asked after the join: this server may already have reported the player as its own, hiding the server they
        // came from. Not known, so no "joined" (a switch must never look like a join).
        assertThat(NetworkPresenceRules.isNetworkJoin(ALEX, "live", new NetworkOnline.Snapshot(nobody, join.plusMillis(1)), join)).isFalse();
        assertThat(NetworkPresenceRules.isNetworkJoin(ALEX, "live", null, join)).isFalse(); // wos-api did not answer
        assertThat(NetworkPresenceRules.isNetworkJoin(ALEX, "live",
                new NetworkOnline.Snapshot(List.of(new NetworkPlayer(ALEX, "Alex", "hub")), join.minusSeconds(1)), join)).isFalse();
    }

    @Test
    void quitWithoutAJoinElsewhereIsALeave() {
        assertThat(NetworkPresenceRules.isNetworkQuit(QUIT, null, false)).isTrue();
        // A join on another server long before (an earlier visit) does not count.
        assertThat(NetworkPresenceRules.isNetworkQuit(QUIT, QUIT.minusSeconds(600), false)).isTrue();
        // Nor one from a quick visit a few seconds before (there and back, then gone).
        assertThat(NetworkPresenceRules.isNetworkQuit(QUIT, QUIT.minusSeconds(8), false)).isTrue();
    }

    @Test
    void switchIsNotLeave() {
        // The join on the new server arrives just before or after this server's quit.
        assertThat(NetworkPresenceRules.isNetworkQuit(QUIT, QUIT.minusSeconds(2), false)).isFalse();
        assertThat(NetworkPresenceRules.isNetworkQuit(QUIT, QUIT.plusSeconds(2), false)).isFalse();
    }

    @Test
    void backOnThisServerIsNotLeave() {
        assertThat(NetworkPresenceRules.isNetworkQuit(QUIT, null, true)).isFalse();
    }

    @Test
    void findsByNameIgnoringCase() {
        List<NetworkPlayer> online = List.of(new NetworkPlayer(ALEX, "Alex", "hub"), new NetworkPlayer(SAM, "Sam", "live"));
        assertThat(NetworkPresenceRules.find("alex", online)).contains(new NetworkPlayer(ALEX, "Alex", "hub"));
        assertThat(NetworkPresenceRules.find("Nobody", online)).isEmpty();
    }
}
