package me.hektortm.wosCore.server;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The check of PUT /v1/server/self's answer against this server's own id and tag. */
class ServerRegistrarTest {
    private static final ServerIdentity STAGING = new ServerIdentity("staging", "staging");

    @Test
    void sameIdAndTagIsFine() {
        assertThat(ServerRegistrar.mismatch(STAGING, JsonParser.parseString("{\"id\":\"staging\",\"tag\":\"staging\"}"))).isNull();
    }

    @Test
    void anOldApiAnswering204IsNotChecked() {
        assertThat(ServerRegistrar.mismatch(STAGING, null)).isNull();
        assertThat(ServerRegistrar.mismatch(STAGING, JsonParser.parseString("[]"))).isNull();
        assertThat(ServerRegistrar.mismatch(STAGING, JsonParser.parseString("{}"))).isNull();
    }

    @Test
    void differentIdOrTagIsReported() {
        String msg = ServerRegistrar.mismatch(STAGING, JsonParser.parseString("{\"id\":\"live\",\"tag\":\"live\"}"));
        assertThat(msg).isEqualTo("Server id/tag mismatch: this server is staging/staging but its API token belongs to "
                + "live/live — hand-off and messages will fail. Set WOS_SERVER_ID/WOS_SERVER_TAG to match the token.");
        assertThat(ServerRegistrar.mismatch(STAGING, JsonParser.parseString("{\"id\":\"staging-2\",\"tag\":\"staging\"}")))
                .contains("staging-2/staging");
        assertThat(ServerRegistrar.mismatch(STAGING, JsonParser.parseString("{\"id\":\"staging\",\"tag\":\"backend\"}")))
                .contains("staging/backend");
    }

    @Test
    void realmMismatchDisables() {
        // A staging server holding a live token would write test play into live.
        ServerRegistrar.Check c = ServerRegistrar.check(STAGING, JsonParser.parseString("{\"id\":\"live\",\"tag\":\"live\"}"));
        assertThat(c.disable()).isTrue();
        assertThat(c.message()).isEqualTo("Server realm mismatch: this server is staging/staging (realm test) but its API "
                + "token belongs to live/live (realm live) — its writes would land in the wrong realm. Core and the WoS "
                + "plugins are disabled. Set WOS_SERVER_ID/WOS_SERVER_TAG to match the token.");
        assertThat(ServerRegistrar.check(new ServerIdentity("live", "live"), JsonParser.parseString("{\"id\":\"backend\",\"tag\":\"backend\"}"))
                .disable()).isTrue();
        // The unconfigured legacy server is realm live: only a test token is a realm mismatch.
        assertThat(ServerRegistrar.check(ServerIdentity.defaults(), JsonParser.parseString("{\"id\":\"staging\",\"tag\":\"staging\"}"))
                .disable()).isTrue();
        ServerRegistrar.Check legacy = ServerRegistrar.check(ServerIdentity.defaults(), JsonParser.parseString("{\"id\":\"hub\",\"tag\":\"hub\"}"));
        assertThat(legacy.disable()).isFalse();
        assertThat(legacy.message()).contains("hub/hub");
    }

    @Test
    void sameRealmMismatchOnlyLogs() {
        ServerRegistrar.Check otherId = ServerRegistrar.check(STAGING, JsonParser.parseString("{\"id\":\"staging-2\",\"tag\":\"staging\"}"));
        assertThat(otherId.disable()).isFalse();
        assertThat(otherId.message()).contains("staging-2/staging");
        ServerRegistrar.Check otherTag = ServerRegistrar.check(STAGING, JsonParser.parseString("{\"id\":\"backend\",\"tag\":\"backend\"}"));
        assertThat(otherTag.disable()).isFalse();
        assertThat(otherTag.message()).startsWith("Server id/tag mismatch");
        ServerRegistrar.Check hubOnLive = ServerRegistrar.check(new ServerIdentity("live", "live"), JsonParser.parseString("{\"id\":\"hub\",\"tag\":\"hub\"}"));
        assertThat(hubOnLive.disable()).isFalse();
        // A tag this Core does not know: no realm to compare, only the log line.
        ServerRegistrar.Check unknown = ServerRegistrar.check(STAGING, JsonParser.parseString("{\"id\":\"x\",\"tag\":\"lobby\"}"));
        assertThat(unknown.disable()).isFalse();
        assertThat(unknown.message()).isNotNull();
        ServerRegistrar.Check same = ServerRegistrar.check(STAGING, JsonParser.parseString("{\"id\":\"staging\",\"tag\":\"staging\"}"));
        assertThat(same.disable()).isFalse();
        assertThat(same.message()).isNull();
    }

    @Test
    void oldApiNoCheck() {
        for (String body : new String[]{"[]", "{}", "{\"id\":\"live\"}", "{\"tag\":\"live\"}"}) {
            ServerRegistrar.Check c = ServerRegistrar.check(STAGING, JsonParser.parseString(body));
            assertThat(c.disable()).as(body).isFalse();
            assertThat(c.message()).as(body).isNull();
        }
        ServerRegistrar.Check none = ServerRegistrar.check(STAGING, null); // 204 without a body
        assertThat(none.disable()).isFalse();
        assertThat(none.message()).isNull();
    }
}
