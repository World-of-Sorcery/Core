package me.hektortm.wosCore.net;

import me.hektortm.wosCore.WoSCore;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Asks the Velocity proxy to move a player to another server, with the
 * BungeeCord {@code Connect} plugin message on the {@code BungeeCord} channel
 * (Velocity answers it when {@code bungee-plugin-message-channel = true}).
 * The name must be the server's name in Velocity's configuration.
 *
 * <p>Main thread only: it sends a plugin message through the player's
 * connection. The outgoing channel is registered by {@link WoSCore} at enable.</p>
 */
public final class ServerConnect {
    public static final String CHANNEL = "BungeeCord";

    private ServerConnect() {}

    /** The payload of the {@code Connect} message: the sub-channel, then the server's name. */
    static byte[] message(String server) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF("Connect");
            out.writeUTF(server);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // a byte array does not fail
        }
        return bytes.toByteArray();
    }

    /** Sends {@code p} to {@code server}. Main thread only. */
    public static void send(Player p, String server) {
        p.sendPluginMessage(WoSCore.getPlugin(WoSCore.class), CHANNEL, message(server));
    }
}
