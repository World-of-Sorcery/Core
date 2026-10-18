package me.hektortm.wosCore.presence;

import java.util.UUID;

/**
 * A player online somewhere on the network, as wos-api lists them.
 *
 * @param server id of the game server the player is on
 */
public record NetworkPlayer(UUID uuid, String username, String server) {}
