package com.tabl.signtest;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

/** Formats config-supplied message templates: '&'-color codes, newlines, and placeholders. */
final class Msg {

	private Msg() {
	}

	static String fill(String raw, String mod, String player, String duration, int offenseNumber) {
		return raw
				.replace("{mod}", mod)
				.replace("{player}", player)
				.replace("{duration}", duration)
				.replace("{offense}", String.valueOf(offenseNumber));
	}

	static Component colorize(String raw) {
		String legacy = ChatColor.translateAlternateColorCodes('&', raw);
		return LegacyComponentSerializer.legacySection().deserialize(legacy);
	}
}
