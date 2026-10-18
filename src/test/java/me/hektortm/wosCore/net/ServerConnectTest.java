package me.hektortm.wosCore.net;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ServerConnectTest {

    @Test
    void theMessageIsConnectThenTheServerName() throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(ServerConnect.message("live")));
        assertThat(in.readUTF()).isEqualTo("Connect");
        assertThat(in.readUTF()).isEqualTo("live");
        assertThat(in.available()).isZero();
    }
}
