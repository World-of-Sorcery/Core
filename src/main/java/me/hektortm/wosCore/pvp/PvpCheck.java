package me.hektortm.wosCore.pvp;

import org.bukkit.entity.Player;

/**
 * Whether one player may hurt another where they are now. The PvP plugin owns the rule and
 * registers it in Bukkit's services manager; other plugins (spells in Systems) look it up:
 * {@code Bukkit.getServicesManager().load(PvpCheck.class)}. Null there means the PvP plugin
 * isn't running.
 */
@FunctionalInterface
public interface PvpCheck {

    boolean mayHurt(Player attacker, Player victim);
}
