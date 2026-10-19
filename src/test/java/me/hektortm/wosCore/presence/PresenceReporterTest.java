package me.hektortm.wosCore.presence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The online list sent to wos-api. */
class PresenceReporterTest {
    private static final UUID HEKTOR = UUID.fromString("28c63f65-52cc-47f0-8246-b16f2176da23");
    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final List<PresenceReporter.Online> ONLINE =
            List.of(new PresenceReporter.Online(HEKTOR, "HektorTM"), new PresenceReporter.Online(NOTCH, "Notch"));

    @Test
    void reportsEveryoneOnline() {
        assertThat(PresenceReporter.body(ONLINE, null)).containsExactly(
                Map.<String, Object>of("uuid", HEKTOR.toString(), "username", "HektorTM"),
                Map.<String, Object>of("uuid", NOTCH.toString(), "username", "Notch"));
    }

    @Test
    void leavesOutThePlayerWhoIsQuitting() {
        assertThat(PresenceReporter.body(ONLINE, NOTCH))
                .containsExactly(Map.<String, Object>of("uuid", HEKTOR.toString(), "username", "HektorTM"));
    }

    @Test
    void sendsTheSkinWhenThePlayerHasOne() {
        assertThat(PresenceReporter.body(List.of(new PresenceReporter.Online(HEKTOR, "HektorTM", "tex", "sig")), null))
                .containsExactly(Map.<String, Object>of("uuid", HEKTOR.toString(), "username", "HektorTM",
                        "skin", Map.of("value", "tex", "signature", "sig")));
    }

    @Test
    void anEmptyServerIsAnEmptyList() {
        assertThat(PresenceReporter.body(List.of(), null)).isEmpty();
    }

    @Test
    void withoutTheBusAStopAlwaysEmptiesTheList() {
        assertThat(PresenceReporter.emptiesOnStop(false, 0)).isTrue();
        assertThat(PresenceReporter.emptiesOnStop(false, 12)).isTrue();
    }

    @Test
    void withTheBusAStopKeepsPlayersStillOnlineListed() {
        // they move to the hub: its report takes the rows over, so it reads as a switch
        assertThat(PresenceReporter.emptiesOnStop(true, 12)).isFalse();
    }

    @Test
    void withTheBusAnEmptyServerStillEmptiesTheList() {
        assertThat(PresenceReporter.emptiesOnStop(true, 0)).isTrue();
    }
}
