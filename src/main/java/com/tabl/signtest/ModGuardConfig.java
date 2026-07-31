package com.tabl.signtest;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loaded view of config.yml. */
final class ModGuardConfig {

	final boolean enabled;
	final boolean checkOnJoin;
	final int joinDelayTicks;
	final long canaryTimeoutTicks;
	final long betweenChecksTicks;
	final String action; // "kick", "alert", or "escalate"
	final String kickMessage;
	final String alertPermission;
	final List<ModDefinition> mods;

	// Used only when action = "escalate". Offense #1 uses tiers.get(0),
	// offense #2 uses tiers.get(1), etc.; once the offense count exceeds the
	// list, the last tier repeats indefinitely.
	final List<EscalationTier> escalationTiers;

	private ModGuardConfig(boolean enabled, boolean checkOnJoin, int joinDelayTicks, long canaryTimeoutTicks,
			long betweenChecksTicks, String action, String kickMessage, String alertPermission,
			List<ModDefinition> mods, List<EscalationTier> escalationTiers) {
		this.enabled = enabled;
		this.checkOnJoin = checkOnJoin;
		this.joinDelayTicks = joinDelayTicks;
		this.canaryTimeoutTicks = canaryTimeoutTicks;
		this.betweenChecksTicks = betweenChecksTicks;
		this.action = action;
		this.kickMessage = kickMessage;
		this.alertPermission = alertPermission;
		this.mods = mods;
		this.escalationTiers = escalationTiers;
	}

	static ModGuardConfig load(FileConfiguration config) {
		boolean enabled = config.getBoolean("enabled", true);
		boolean checkOnJoin = config.getBoolean("check-on-join", true);
		int joinDelayTicks = config.getInt("join-delay-ticks", 40);
		long canaryTimeoutTicks = config.getLong("canary-timeout-ticks", 60); // 3s, safety net only
		long betweenChecksTicks = config.getLong("between-checks-ticks", 10); // 0.5s
		String action = config.getString("action", "kick").toLowerCase(Locale.ROOT);
		String kickMessage = config.getString("kick-message", "Disallowed client modification detected.");
		String alertPermission = config.getString("alert-permission", "signtest.alert");

		List<EscalationTier> tiers = new ArrayList<>();
		for (Map<?, ?> raw : config.getMapList("escalation.tiers")) {
			Object actionRaw = raw.get("action");
			Object messageRaw = raw.get("message");
			String tierAction = (actionRaw != null ? String.valueOf(actionRaw) : "kick").toLowerCase(Locale.ROOT);
			String message = messageRaw != null ? String.valueOf(messageRaw) : "Disallowed client modification detected.";
			Object durationRaw = raw.get("duration-minutes");
			long duration = durationRaw != null ? Long.parseLong(String.valueOf(durationRaw)) : 0;
			tiers.add(new EscalationTier(tierAction, message, duration));
		}
		if (tiers.isEmpty()) {
			tiers.add(new EscalationTier("kick", kickMessage, 0));
		}

		List<ModDefinition> mods = new ArrayList<>();
		ConfigurationSection section = config.getConfigurationSection("mods");
		if (section != null) {
			for (String name : section.getKeys(false)) {
				if (section.isConfigurationSection(name)) {
					// Rich form: name: { key: "...", mode: translate|keybind }
					ConfigurationSection modSection = section.getConfigurationSection(name);
					String key = modSection.getString("key");
					if (key == null || key.isBlank()) {
						continue;
					}
					String modeRaw = modSection.getString("mode", "translate");
					DetectionMode mode = "keybind".equalsIgnoreCase(modeRaw)
							? DetectionMode.KEYBIND : DetectionMode.TRANSLATE;
					mods.add(new ModDefinition(name, key, mode));
				} else {
					// Simple form: name: "key" — defaults to TRANSLATE mode.
					String key = section.getString(name);
					if (key != null && !key.isBlank()) {
						mods.add(new ModDefinition(name, key, DetectionMode.TRANSLATE));
					}
				}
			}
		}

		return new ModGuardConfig(enabled, checkOnJoin, joinDelayTicks, canaryTimeoutTicks, betweenChecksTicks,
				action, kickMessage, alertPermission, mods, tiers);
	}
}
