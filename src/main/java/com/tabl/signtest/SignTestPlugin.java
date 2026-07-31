package com.tabl.signtest;

import org.bukkit.plugin.java.JavaPlugin;

public final class SignTestPlugin extends JavaPlugin {

	private ModGuardConfig modGuardConfig;
	private OffenseStore offenseStore;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		modGuardConfig = ModGuardConfig.load(getConfig());
		offenseStore = new OffenseStore(this);

		SignTestCommand command = new SignTestCommand(this);
		getCommand("signtest").setExecutor(command);
		getCommand("signtest").setTabCompleter(command);

		getServer().getPluginManager().registerEvents(new ModGuardListener(this), this);

		getLogger().info("SignTestPlugin enabled. " + modGuardConfig.mods.size()
				+ " mod signature(s) configured; check-on-join="
				+ (modGuardConfig.enabled && modGuardConfig.checkOnJoin)
				+ "; action=" + modGuardConfig.action + ".");
	}

	ModGuardConfig modGuardConfig() {
		return modGuardConfig;
	}

	OffenseStore offenseStore() {
		return offenseStore;
	}

	void reloadModGuardConfig() {
		reloadConfig();
		modGuardConfig = ModGuardConfig.load(getConfig());
	}
}
