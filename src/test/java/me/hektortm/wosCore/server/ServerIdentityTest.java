package me.hektortm.wosCore.server;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerIdentityTest {

    private static YamlConfiguration yaml(String id, String tag) {
        YamlConfiguration c = new YamlConfiguration();
        if (id != null) c.set("id", id);
        if (tag != null) c.set("tag", tag);
        return c;
    }

    @Test
    void fromDefaultsToLive() {
        ServerIdentity i = ServerIdentity.from(null, Map.of());
        assertThat(i.id()).isEqualTo("live");
        assertThat(i.tag()).isEqualTo("live");
        assertThat(i.isLive()).isTrue();
        assertThat(i.configured()).isFalse();
        assertThat(i.takesContentReloads()).isTrue(); // legacy single server
        assertThat(ServerIdentity.from(yaml(null, null), Map.of())).isEqualTo(i);
        assertThat(ServerIdentity.from(yaml("", " "), Map.of())).isEqualTo(i);
    }

    @Test
    void yamlIsUsed() {
        ServerIdentity i = ServerIdentity.from(yaml("hub-1", "hub"), Map.of());
        assertThat(i.id()).isEqualTo("hub-1");
        assertThat(i.isHub()).isTrue();
        assertThat(i.realm()).isEqualTo("live");
    }

    @Test
    void envOverridesYaml() {
        ServerIdentity i = ServerIdentity.from(yaml("live", "live"),
                Map.of("WOS_SERVER_ID", "stage-a", "WOS_SERVER_TAG", "staging"));
        assertThat(i.id()).isEqualTo("stage-a");
        assertThat(i.tag()).isEqualTo("staging");
        assertThat(i.takesContentReloads()).isTrue();
    }

    @Test
    void realmOfStagingIsTest() {
        assertThat(new ServerIdentity("a", "staging").realm()).isEqualTo("test");
        assertThat(new ServerIdentity("a", "backend").realm()).isEqualTo("test");
        assertThat(new ServerIdentity("a", "hub").realm()).isEqualTo("live");
        assertThat(new ServerIdentity("a", "live").realm()).isEqualTo("live");
        assertThat(new ServerIdentity("a", "backend").takesContentReloads()).isTrue();
    }

    @Test
    void configuredOnlyWhenTheTagWasSet() {
        assertThat(ServerIdentity.from(null, Map.of()).configured()).isFalse();
        assertThat(ServerIdentity.from(yaml("x", " "), Map.of("WOS_SERVER_TAG", "")).configured()).isFalse();
        assertThat(ServerIdentity.from(yaml("hub-1", null), Map.of()).configured()).isFalse(); // only the id was set
        assertThat(ServerIdentity.from(yaml(null, "live"), Map.of()).configured()).isTrue();
        assertThat(ServerIdentity.from(null, Map.of("WOS_SERVER_TAG", "staging")).configured()).isTrue();
        // Not configured = the legacy single server, which takes every reload.
        assertThat(ServerIdentity.from(null, Map.of()).takesContentReloads()).isTrue();
        assertThat(ServerIdentity.from(yaml(null, "live"), Map.of()).takesContentReloads()).isFalse();
    }

    @Test
    void unknownTagIsRejected() {
        assertThatThrownBy(() -> ServerIdentity.from(yaml("x", "prod"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServerIdentity.from(yaml("x", "live"), Map.of("WOS_SERVER_TAG", "stagin")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
