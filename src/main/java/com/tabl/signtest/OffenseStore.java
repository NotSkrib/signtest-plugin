package com.tabl.signtest;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Tracks how many times each player has been caught by the join-time mod
 * guard, persisted to offenses.yml so it survives restarts. Used to
 * implement the kick-then-ban escalation in ModGuardListener.
 */
final class OffenseStore {

	private final SignTestPlugin plugin;
	private final File file;
	private final FileConfiguration config;

	OffenseStore(SignTestPlugin plugin) {
		this.plugin = plugin;
		this.file = new File(plugin.getDataFolder(), "offenses.yml");
		this.config = YamlConfiguration.loadConfiguration(file);
	}

	int getOffenseCount(UUID uuid) {
		return config.getInt(uuid.toString() + ".count", 0);
	}

	void recordOffense(UUID uuid, String modName) {
		String path = uuid.toString();
		int count = config.getInt(path + ".count", 0) + 1;
		config.set(path + ".count", count);
		config.set(path + ".lastMod", modName);
		config.set(path + ".lastTimeMillis", System.currentTimeMillis());
		save();
	}

	void clear(UUID uuid) {
		config.set(uuid.toString(), null);
		save();
	}

	private void save() {
		try {
			config.save(file);
		} catch (IOException e) {
			plugin.getLogger().log(Level.WARNING, "Failed to save offenses.yml", e);
		}
	}
}
