package me.hektortm.wosCore.presence;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * A player's rank as LuckPerms has it: the prefix chat and the tab list show before their name
 * (colour codes as set on the group, "" for none) and the weight the tab list sorts by (the
 * heaviest group they are in, 0 for none). The servers report it with who is online, so the
 * other servers' tab lists show and sort them the same way.
 */
public record PlayerRank(String prefix, int weight) {
    public static final PlayerRank NONE = new PlayerRank("", 0);

    /** An online player's rank from LuckPerms' cached data; {@link #NONE} without LuckPerms or before it loaded them. */
    public static PlayerRank of(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return NONE;
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getPlayerAdapter(Player.class).getUser(player);
            String prefix = user.getCachedData().getMetaData().getPrefix();
            int weight = user.getInheritedGroups(user.getQueryOptions()).stream()
                    .mapToInt(PlayerRank::weightOf)
                    .max().orElse(0);
            return new PlayerRank(prefix == null ? "" : prefix, weight);
        } catch (IllegalStateException notLoaded) {
            return NONE; // LuckPerms not ready, or the player's data not loaded (yet)
        }
    }

    private static int weightOf(Group group) {
        return group.getWeight().orElse(0);
    }
}
