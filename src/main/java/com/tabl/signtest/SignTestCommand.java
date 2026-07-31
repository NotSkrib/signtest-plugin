package com.tabl.signtest;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /signtest <player> <key> [translate|keybind] — manual one-off canary
 * check, useful for confirming a mod's key (and which mode actually gets a
 * response from it) before adding it to config.yml's automatic join-time
 * guard (see ModGuardListener). Mode defaults to translate if omitted.
 *
 * /signtest reload — reloads config.yml without restarting the server.
 * /signtest clearoffense <player> — resets a player's escalation offense
 * count (see config.yml `action: escalate`), for use after they've
 * genuinely removed the flagged mod.
 */
public class SignTestCommand implements CommandExecutor, TabCompleter {

	private final SignTestPlugin plugin;

	public SignTestCommand(SignTestPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
			plugin.reloadModGuardConfig();
			sender.sendMessage("SignTestPlugin config reloaded — "
					+ plugin.modGuardConfig().mods.size() + " mod signature(s) configured.");
			return true;
		}

		if (args.length == 2 && args[0].equalsIgnoreCase("clearoffense")) {
			OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
			plugin.offenseStore().clear(target.getUniqueId());
			sender.sendMessage("Cleared escalation offense record for " + args[1] + ".");
			return true;
		}

		if (args.length != 2 && args.length != 3) {
			sender.sendMessage("Usage: /signtest <player> <key> [translate|keybind]  |  /signtest reload  |  /signtest clearoffense <player>");
			return true;
		}

		Player target = Bukkit.getPlayerExact(args[0]);
		if (target == null) {
			sender.sendMessage("Player '" + args[0] + "' is not online.");
			return true;
		}

		String canaryKey = args[1];
		DetectionMode mode = (args.length == 3 && "keybind".equalsIgnoreCase(args[2]))
				? DetectionMode.KEYBIND : DetectionMode.TRANSLATE;
		ModDefinition mod = new ModDefinition(canaryKey, canaryKey, mode);

		// The editor auto-closes almost immediately on its own now (see
		// CanaryCheck's class doc) — there's no click-it-yourself window to
		// give a generous timer for anymore, this is purely a backstop.
		long timeoutTicks = plugin.modGuardConfig().canaryTimeoutTicks;
		sender.sendMessage("Placed canary sign with key '" + canaryKey + "' (" + mode + ") near "
				+ target.getName() + "...");

		CanaryCheck.run(plugin, target, List.of(mod), timeoutTicks, results -> {
			String echoed = results.length > 0 ? results[0] : "";
			reportResult(sender, target, mod, echoed);
		});
		return true;
	}

	private void reportResult(CommandSender sender, Player target, ModDefinition mod, String plainEcho) {
		boolean leaked = CanaryCheck.isLeaked(mod, plainEcho);
		sender.sendMessage("--- signtest result for " + target.getName() + " ---");
		sender.sendMessage("Canary key: " + mod.key() + " (" + mod.mode() + ")");
		sender.sendMessage("Echoed text: '" + plainEcho + "'");
		if (leaked) {
			if (mod.mode() == DetectionMode.TRANSLATE && plainEcho.equals(mod.key())) {
				sender.sendMessage("RESULT: LEAKED — the client echoed the literal key text instead of "
						+ "the not-installed marker, which some protected clients (e.g. Meteor Client) "
						+ "substitute instead of resolving normally. Something recognized this key.");
			} else {
				sender.sendMessage("RESULT: LEAKED — the client resolved this key to '" + plainEcho
						+ "', meaning whatever mod registers it is installed.");
			}
		} else {
			sender.sendMessage("RESULT: CLEAN — the client echoed "
					+ (mod.mode() == DetectionMode.KEYBIND ? "the raw key" : "the not-installed marker")
					+ " (or nothing).");
		}
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		List<String> options = new ArrayList<>();
		if (args.length == 1) {
			options.add("reload");
			options.add("clearoffense");
			for (Player p : Bukkit.getOnlinePlayers()) {
				options.add(p.getName());
			}
		} else if (args.length == 2) {
			if (args[0].equalsIgnoreCase("clearoffense")) {
				for (Player p : Bukkit.getOnlinePlayers()) {
					options.add(p.getName());
				}
			} else {
				options.add("key.meteor-client.example");
				options.add("your.canary.key.here");
			}
		} else if (args.length == 3) {
			options.add("translate");
			options.add("keybind");
		}
		return options;
	}
}
