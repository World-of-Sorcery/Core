package me.hektortm.wosCore.presence;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A player online somewhere on the network, as wos-api lists them.
 *
 * @param server        id of the game server the player is on
 * @param serverTag     that server's tag ({@code hub}, {@code live} …), "" when wos-api does not know it
 * @param prefix        the display of the prefix the player has equipped on live, "" for none
 * @param skinValue     the player's signed "textures" property, {@code null} when their server sent none
 * @param skinSignature its signature, {@code null} with the value
 */
public record NetworkPlayer(UUID uuid, String username, String server, String serverTag, String prefix,
                            @Nullable String skinValue, @Nullable String skinSignature) {

    /** A player without tab list details (no tag, prefix or skin). */
    public NetworkPlayer(UUID uuid, String username, String server) {
        this(uuid, username, server, "", "", null, null);
    }

    /** Whether the player is on a hub. */
    public boolean onHub() {
        return "hub".equals(serverTag);
    }
}
