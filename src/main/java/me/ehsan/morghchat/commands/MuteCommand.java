package me.ehsan.morghchat.commands;

import me.ehsan.morghchat.MorghChat;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Explicit /mute and /unmute commands for global-channel and player-channel moderation. */
public final class MuteCommand implements CommandExecutor {
    private static final Set<String> CHANNELS = Set.of("local", "global", "staff", "donator", "admin", "highrank");
    private final MorghChat plugin;
    public MuteCommand(MorghChat plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean muted = command.getName().equalsIgnoreCase("mute");
        if (!sender.hasPermission("morghchat.mute") && !sender.hasPermission("morghchat.admin")) {
            plugin.getMessagesManager().send(sender, "chat.management-no-permission", "<red>You don't have permission to manage chat mutes.</red>");
            return true;
        }
        if (args.length < 1 || args.length > 2) { usage(sender, label); return true; }
        String first = args[0].toLowerCase(Locale.ROOT);
        String channel;
        if (CHANNELS.contains(first) && args.length == 1) {
            channel = first;
            plugin.getMuteManager().setGlobalMuted(channel, muted);
            send(sender, "mute.channel-muted", "<green>{channel} chat has been {state} globally.</green>",
                    Map.of("channel", channel.toUpperCase(Locale.ROOT), "state", state(muted)));
            return true;
        }
        String playerName = args[0];
        channel = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : "local";
        if (!CHANNELS.contains(channel)) {
            plugin.getMessagesManager().send(sender, "mute.invalid-channel", "<red>Unknown channel. Choose local, global, staff, donator, admin, or highrank.</red>");
            return true;
        }
        Player online = Bukkit.getPlayerExact(playerName);
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayerIfCached(playerName);
        if (target == null || target.getName() == null) {
            plugin.getMessagesManager().send(sender, "mute.unknown-target", "<red>Unknown player. The player must have joined this server before.</red>");
            return true;
        }
        plugin.getMuteManager().setPlayerMuted(channel, target.getUniqueId(), muted);
        send(sender, "mute.player-muted", "<green>{player} has been {state} in {channel} chat.</green>",
                Map.of("player", target.getName(), "state", state(muted), "channel", channel.toUpperCase(Locale.ROOT)));
        return true;
    }

    private String state(boolean muted) {
        return plugin.getMessagesManager().raw(muted ? "chat.mute-state-muted" : "chat.mute-state-unmuted", muted ? "muted" : "unmuted");
    }
    private void usage(CommandSender sender, String label) {
        plugin.getMessagesManager().send(sender, "mute.usage", "<red>Usage: /{action} <player|channel> [channel]</red>", Map.of("action", label));
    }
    private void send(CommandSender sender, String path, String fallback, Map<String, ?> placeholders) {
        plugin.getMessagesManager().send(sender, path, fallback, placeholders);
    }
}
