package me.hektortm.wosCore.presence;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * When a join or leave is one of the <b>network</b>, not a switch between two servers.
 *
 * <p>With Velocity the player logs in on the new server <i>before</i> the old one sees them
 * quit. So on join the API's online list still names the old server (a switch, no "joined"),
 * and the old server holds back its "left" for {@link #QUIT_DELAY}: a join for that player on
 * another server close to the quit (before or after it) makes it a switch.</p>
 */
public final class NetworkPresenceRules {
    /** How long a server waits after a quit before it says the player left. */
    public static final Duration QUIT_DELAY = Duration.ofSeconds(3);
    /** A join this close before a quit (or any time after it) belongs to a switch. */
    public static final Duration JOIN_GRACE = Duration.ofSeconds(5);

    private NetworkPresenceRules() {}

    /**
     * Whether a player who just joined {@code selfServer} entered the network: true unless the
     * online list (taken as they log in) has them on another server.
     */
    public static boolean isNetworkJoin(UUID player, String selfServer, Collection<NetworkPlayer> online) {
        return online.stream().noneMatch(p -> p.uuid().equals(player) && !p.server().equals(selfServer));
    }

    /**
     * As {@link #isNetworkJoin(UUID, String, Collection)}, for a player who joined at {@code joinedAt}. Only a
     * list asked for before the join can tell: after it this server may already have reported the player
     * as its own, which hides the server they came from. A later list, or none (wos-api did not answer),
     * is not known: false, so a switch never looks like a join (a missing "joined" over a wrong one).
     */
    public static boolean isNetworkJoin(UUID player, String selfServer, @Nullable NetworkOnline.Snapshot online, Instant joinedAt) {
        if (online == null || online.askedAt().isAfter(joinedAt)) return false;
        return isNetworkJoin(player, selfServer, online.players());
    }

    /**
     * Whether a quit at {@code quitAt} left the network, decided {@link #QUIT_DELAY} later.
     *
     * @param lastJoinElsewhere when another server last said this player joined; null if never
     * @param backHere          whether the player is online on this server again
     */
    public static boolean isNetworkQuit(Instant quitAt, @Nullable Instant lastJoinElsewhere, boolean backHere) {
        if (backHere) return false;
        return lastJoinElsewhere == null || lastJoinElsewhere.isBefore(quitAt.minus(JOIN_GRACE));
    }

    /** The online player with this name (ignoring case). */
    public static Optional<NetworkPlayer> find(String name, Collection<NetworkPlayer> online) {
        String n = name.toLowerCase(Locale.ROOT);
        return online.stream().filter(p -> p.username().toLowerCase(Locale.ROOT).equals(n)).findFirst();
    }
}
