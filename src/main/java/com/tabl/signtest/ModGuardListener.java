package com.tabl.signtest;

import net.kyori.adventure.text.Component;
import org.bukkit.BanList;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Runs the configured mod-fingerprint checks against each joining player and
 * reacts on a match — see config.yml `action`:
 *   kick     - just disconnect with `kick-message`
 *   alert    - only notify staff, no kick
 *   escalate - walks up `escalation.tiers` on each catch (kick, kick again,
 *              ban, permaban, or however many/whatever tiers you define).
 *              Offense counts persist in offenses.yml across restarts.
 */
final class ModGuardListener implements Listener {

	private final SignTestPlugin plugin;

	ModGuardListener(SignTestPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		ModGuardConfig config = plugin.modGuardConfig();
		if (!config.enabled || !config.checkOnJoin || config.mods.isEmpty()) {
			return;
		}

		Player player = event.getPlayer();
		if (player.hasPermission("signtest.bypass")) {
			return;
		}

		UUID uuid = player.getUniqueId();
		List<ModDefinition> mods = config.mods;

		new BukkitRunnable() {
			@Override
			public void run() {
				Player p = plugin.getServer().getPlayer(uuid);
				if (p != null && p.isOnline()) {
					runChecks(p, mods, 0, config);
				}
			}
		}.runTaskLater(plugin, config.joinDelayTicks);
	}

	/**
	 * Up to 4 mods per sign (one per line), signs run sequentially with a
	 * gap between each. An earlier version of this deliberately capped it
	 * at 1 per sign after seeing a protected client stop resolving its own
	 * key on a multi-key sign — but that was tested against the old,
	 * flaky block-removal auto-close mechanism, not the current
	 * fake-block-change one that actually matches how the reference
	 * implementation (CheckHacks) does it, batching included. Worth
	 * retrying batched now that the underlying mechanism is fixed.
	 */
	private void runChecks(Player player, List<ModDefinition> mods, int startIndex, ModGuardConfig config) {
		if (!player.isOnline() || startIndex >= mods.size()) {
			return;
		}

		int endIndex = Math.min(startIndex + 4, mods.size());
		List<ModDefinition> batch = mods.subList(startIndex, endIndex);

		CanaryCheck.run(plugin, player, batch, config.canaryTimeoutTicks, results -> {
			for (int i = 0; i < results.length; i++) {
				ModDefinition mod = batch.get(i);
				String echoed = results[i];
				if (CanaryCheck.isLeaked(mod, echoed)) {
					handleDetection(player, mod, echoed, config);
					return;
				}
			}
			new BukkitRunnable() {
				@Override
				public void run() {
					runChecks(player, mods, endIndex, config);
				}
			}.runTaskLater(plugin, config.betweenChecksTicks);
		});
	}

	private void handleDetection(Player player, ModDefinition mod, String echoed, ModGuardConfig config) {
		String modName = mod.name();
		plugin.getLogger().warning(String.format(
				"%s appears to be running '%s' (key '%s' resolved to '%s')",
				player.getName(), modName, mod.key(), echoed));

		String alertText = String.format("[SignTest] %s flagged for '%s'", player.getName(), modName);
		plugin.getServer().getOnlinePlayers().stream()
				.filter(p -> p.hasPermission(config.alertPermission))
				.forEach(p -> p.sendMessage(Component.text(alertText)));

		switch (config.action) {
			case "kick" -> kickNextTick(player, Msg.colorize(config.kickMessage.replace("{mod}", modName)));
			case "escalate" -> escalate(player, modName, config);
			default -> { /* alert only */ }
		}
	}

	private void escalate(Player player, String modName, ModGuardConfig config) {
		UUID uuid = player.getUniqueId();
		int priorOffenses = plugin.offenseStore().getOffenseCount(uuid);
		plugin.offenseStore().recordOffense(uuid, modName);
		int offenseNumber = priorOffenses + 1;

		List<EscalationTier> tiers = config.escalationTiers;
		EscalationTier tier = tiers.get(Math.min(offenseNumber - 1, tiers.size() - 1));

		boolean permanent = tier.banDurationMinutes <= 0;
		String durationText = permanent ? "permanently" : formatDuration(tier.banDurationMinutes);
		String filled = Msg.fill(tier.message, modName, player.getName(), durationText, offenseNumber);
		Component styled = Msg.colorize(filled);

		if ("ban".equals(tier.action)) {
			Date expiry = permanent ? null : new Date(System.currentTimeMillis() + tier.banDurationMinutes * 60_000L);
			plugin.getServer().getBanList(BanList.Type.NAME).addBan(player.getName(), filled, expiry, "SignTestPlugin");
		}
		kickNextTick(player, styled);
	}

	private void kickNextTick(Player player, Component message) {
		// Kick on the next tick — we're inside a sign-change event callback
		// here, and Bukkit doesn't allow disconnecting a player mid-dispatch
		// cleanly.
		new BukkitRunnable() {
			@Override
			public void run() {
				if (player.isOnline()) {
					player.kick(message);
				}
			}
		}.runTask(plugin);
	}

	private static String formatDuration(long minutes) {
		if (minutes % 1440 == 0) {
			long days = minutes / 1440;
			return days + (days == 1 ? " day" : " days");
		}
		if (minutes % 60 == 0) {
			long hours = minutes / 60;
			return hours + (hours == 1 ? " hour" : " hours");
		}
		return minutes + (minutes == 1 ? " minute" : " minutes");
	}
}
