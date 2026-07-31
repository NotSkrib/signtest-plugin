package com.tabl.signtest;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

/**
 * Sends the sign-editor packets directly instead of going through Bukkit's
 * Player#openSign(Sign), which bundles a block-update packet and the
 * open-editor packet with no gap between them. That matters here: opening
 * the editor before the client has finished applying our text update opens
 * it against stale/empty data, and there's no way to insert a delay inside
 * openSign()'s own internals. This sends the same two packets manually with
 * an explicit tick of separation.
 *
 * Uses reflection against Mojang-mapped NMS names (matches modern
 * Paper/CraftBukkit) rather than a compile-time dependency on internal
 * server classes, so it degrades to a warning instead of a hard crash if a
 * future version renames something.
 *
 * Approach adapted from the (MIT-licensed) CheckHacks plugin's SignUtil:
 * https://github.com/branduzzo/CheckHacks
 */
final class NmsSignUtil {

	private NmsSignUtil() {
	}

	static void setAllowedEditor(Location loc, UUID playerUUID, Plugin plugin) {
		try {
			Object world = loc.getWorld().getClass().getMethod("getHandle").invoke(loc.getWorld());
			Object be = getBlockEntity(world, loc);
			if (be == null) {
				return;
			}
			for (Method m : be.getClass().getMethods()) {
				if (m.getName().equals("setAllowedPlayerEditor") && m.getParameterCount() == 1) {
					m.invoke(be, playerUUID);
					return;
				}
			}
			plugin.getLogger().warning("[NmsSignUtil] setAllowedPlayerEditor method not found on "
					+ be.getClass().getName());
		} catch (Exception e) {
			plugin.getLogger().warning("[NmsSignUtil] setAllowedEditor: " + e);
		}
	}

	static void sendBlockEntityPacket(Player player, Location loc, Plugin plugin) {
		try {
			Object world = loc.getWorld().getClass().getMethod("getHandle").invoke(loc.getWorld());
			Object be = getBlockEntity(world, loc);
			if (be == null) {
				return;
			}
			Class<?> pktClass = Class.forName("net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket");
			Method create = Arrays.stream(pktClass.getMethods())
					.filter(m -> m.getName().equals("create") && m.getParameterCount() == 1)
					.findFirst().orElseThrow();
			sendPacket(player, create.invoke(null, be), plugin);
		} catch (Exception e) {
			plugin.getLogger().warning("[NmsSignUtil] sendBlockEntityPacket: " + e);
		}
	}

	static void sendOpenSignPacket(Player player, Location loc, Plugin plugin) {
		try {
			Class<?> bpClass = Class.forName("net.minecraft.core.BlockPos");
			Class<?> pktClass = Class.forName("net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket");
			Object bp = bpClass.getConstructor(int.class, int.class, int.class)
					.newInstance(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
			Object packet = pktClass.getConstructor(bpClass, boolean.class).newInstance(bp, true);
			sendPacket(player, packet, plugin);
		} catch (Exception e) {
			plugin.getLogger().warning("[NmsSignUtil] sendOpenSignPacket: " + e);
		}
	}

	private static Object getBlockEntity(Object world, Location loc) throws Exception {
		Class<?> bpClass = Class.forName("net.minecraft.core.BlockPos");
		Object bp = bpClass.getConstructor(int.class, int.class, int.class)
				.newInstance(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
		Method getBlockEntity = Arrays.stream(world.getClass().getMethods())
				.filter(m -> m.getName().equals("getBlockEntity") && m.getParameterCount() == 1)
				.findFirst().orElseThrow();
		return getBlockEntity.invoke(world, bp);
	}

	private static void sendPacket(Player player, Object packet, Plugin plugin) {
		try {
			Object handle = player.getClass().getMethod("getHandle").invoke(player);
			Object conn = null;
			for (String name : new String[] {"connection", "networkManager", "playerConnection"}) {
				try {
					Field f;
					try {
						f = handle.getClass().getField(name);
					} catch (NoSuchFieldException ex) {
						f = handle.getClass().getDeclaredField(name);
						f.setAccessible(true);
					}
					Object v = f.get(handle);
					if (v != null) {
						conn = v;
						break;
					}
				} catch (Exception ignored) {
					// try the next candidate field name
				}
			}
			if (conn == null) {
				throw new IllegalStateException("connection field not found on " + handle.getClass().getName());
			}
			Object connection = conn;
			Method send = Arrays.stream(connection.getClass().getMethods())
					.filter(m -> m.getName().equals("send") && m.getParameterCount() == 1
							&& m.getParameterTypes()[0].isAssignableFrom(packet.getClass()))
					.findFirst()
					.or(() -> Arrays.stream(connection.getClass().getMethods())
							.filter(m -> m.getName().equals("send") && m.getParameterCount() == 1)
							.findFirst())
					.orElseThrow(() -> new IllegalStateException("send(Packet) method not found"));
			send.invoke(connection, packet);
		} catch (Exception e) {
			plugin.getLogger().warning("[NmsSignUtil] sendPacket: " + e);
		}
	}
}
