package com.tabl.signtest;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Places a real sign, seeds each line with a mod's key (as either a
 * translatable or a keybind component per its DetectionMode), forces the
 * sign editor open, and reports back what the client echoed for each line.
 * See isLeaked() for how to interpret an echo.
 *
 * Mechanism matches the MIT-licensed CheckHacks plugin
 * (https://github.com/branduzzo/CheckHacks) exactly, having previously
 * reinvented a worse version of the same idea:
 *
 *   1. Write the real key(s) onto a real sign block (server-authoritative;
 *      this is the data source the client resolves against).
 *   2. Send a ClientboundBlockEntityDataPacket so the client has that text
 *      before anything else happens.
 *   3. One tick later, send ClientboundOpenSignEditorPacket to force the
 *      editor open.
 *   4. In the SAME tick as step 3, call Player#sendBlockChange() to tell
 *      just that one player — a fake, client-only packet, the real
 *      server-side block is untouched — that the position is now air.
 *
 * Step 4 is the close trigger, and it's the important part: the client's
 * AbstractSignEditScreen ticks a canEdit() check every frame and
 * auto-closes itself (finishEditing() -> removed(), which is what sends the
 * edited text back to the server) the instant it sees its block entity is
 * gone. Since the client already believes the block is air from the moment
 * the editor opens, this fires almost immediately — no player interaction,
 * no artificial delay to tune, and no real world state ever changes (unlike
 * an earlier version of this that actually removed and restored the real
 * block, which needed a delay to avoid racing the text write, and turned
 * out to be inconsistently effective against at least one protected
 * client). Only the real sign placed in step 1 needs cleanup at the end.
 *
 * IMPORTANT: SignBlockEntity#tick() runs every server tick and silently
 * clears the "allowed editor" UUID the moment the target isn't within 4
 * blocks of the sign, so the sign MUST stay within 4 blocks of the target
 * the whole time it's open, or the server rejects the echoed response as
 * "tried to change non-editable sign" and the check always reports clean.
 * The sign is placed at the target's own feet position for exactly this
 * reason, which also keeps it about as unobtrusive as this technique allows
 * — the block overlaps the player's own model instead of appearing
 * somewhere they'd notice it.
 *
 * Signs have 4 lines, so up to 4 keys are checked per placement — but
 * ModGuardListener deliberately only ever passes 1: testing multiple mods'
 * keys on the same sign at once seems to be enough of a red flag on its own
 * (no real player-written sign would ever contain two different mods'
 * translation keys) that at least one protected client stopped resolving
 * its own key when it saw that pattern, even though the exact same key
 * resolved fine tested alone.
 *
 * Forcibly opening a UI screen on the target's client for a moment is
 * inherent to the technique (see MC-265322) — that part can't be hidden.
 */
final class CanaryCheck {

	/**
	 * Fallback text for TRANSLATE-mode components — deliberately NOT the
	 * same as the raw key. A truly vanilla/no-mod client echoes this marker
	 * back unchanged (nothing recognized the key). Some protected clients
	 * (e.g. Meteor Client, per community testing) substitute the literal raw
	 * key text instead of either this marker or a real resolved value when
	 * they detect a suspicious sign edit — which, if the fallback equalled
	 * the key, would be indistinguishable from "not installed". Using a
	 * distinct marker means an echoed raw key is itself a positive signal
	 * (something recognized this key specially) rather than noise.
	 */
	static final String NOT_INSTALLED_MARKER = "__NOT_INSTALLED__";

	private CanaryCheck() {
	}

	/**
	 * Whether an echoed line indicates the mod is present. TRANSLATE and
	 * KEYBIND need different comparisons: TRANSLATE has an explicit fallback
	 * (NOT_INSTALLED_MARKER) to compare against, but Component.keybind(key)
	 * has no fallback parameter to give it — an unbound/unrecognized keybind
	 * ID echoes back as the raw key itself, so that's what "not installed"
	 * looks like for KEYBIND mode.
	 */
	static boolean isLeaked(ModDefinition mod, String echoed) {
		if (echoed == null || echoed.isBlank()) {
			return false;
		}
		return switch (mod.mode()) {
			case TRANSLATE -> !echoed.equals(NOT_INSTALLED_MARKER);
			case KEYBIND -> !echoed.equals(mod.key());
		};
	}

	static void run(Plugin plugin, Player target, List<ModDefinition> mods, long timeoutTicks,
			Consumer<String[]> onResult) {
		if (mods.isEmpty() || mods.size() > 4) {
			throw new IllegalArgumentException("CanaryCheck supports 1-4 keys per sign (got " + mods.size() + ")");
		}

		Location base = target.getLocation();
		Block block = base.getWorld().getBlockAt(base.getBlockX(), base.getBlockY(), base.getBlockZ());
		Location blockLoc = block.getLocation();

		BlockData originalData = block.getBlockData().clone();
		Material originalType = block.getType();

		block.setType(Material.OAK_SIGN, false);
		BlockState state = block.getState();
		if (!(state instanceof Sign sign)) {
			restore(plugin, block, originalType, originalData);
			onResult.accept(new String[0]);
			return;
		}

		for (int i = 0; i < mods.size(); i++) {
			ModDefinition mod = mods.get(i);
			Component component = switch (mod.mode()) {
				case TRANSLATE -> Component.translatable(mod.key(), NOT_INSTALLED_MARKER);
				case KEYBIND -> Component.keybind(mod.key());
			};
			sign.getSide(Side.FRONT).line(i, component);
		}
		sign.update(true, false);

		String[] results = new String[mods.size()];
		AtomicBoolean resolved = new AtomicBoolean(false);
		PluginManager pm = plugin.getServer().getPluginManager();

		Listener listener = new Listener() {
			@EventHandler
			public void onSignChange(SignChangeEvent event) {
				if (!event.getBlock().equals(block)) {
					return;
				}
				if (resolved.compareAndSet(false, true)) {
					for (int i = 0; i < mods.size(); i++) {
						Component line = event.line(i);
						results[i] = line == null ? "" : PlainTextComponentSerializer.plainText().serialize(line);
						plugin.getLogger().info("[CanaryCheck] line " + i + " mod=" + mods.get(i)
								+ " echoed='" + results[i] + "'");
					}
					HandlerList.unregisterAll(this);
					restore(plugin, block, originalType, originalData);
					onResult.accept(results);
				}
			}
		};
		pm.registerEvents(listener, plugin);

		NmsSignUtil.setAllowedEditor(blockLoc, target.getUniqueId(), plugin);
		NmsSignUtil.sendBlockEntityPacket(target, blockLoc, plugin);

		new BukkitRunnable() {
			@Override
			public void run() {
				if (resolved.get()) {
					return;
				}
				NmsSignUtil.sendOpenSignPacket(target, blockLoc, plugin);
				// Fake, client-only: only the target sees this position as
				// air. The real block (and its tile entity, still holding
				// our text) is untouched. This is what makes the client's
				// own canEdit() check fail and auto-close+submit — see the
				// class doc.
				target.sendBlockChange(blockLoc, org.bukkit.Bukkit.createBlockData(Material.AIR));

				new BukkitRunnable() {
					@Override
					public void run() {
						if (resolved.compareAndSet(false, true)) {
							plugin.getLogger().info("[CanaryCheck] Timed out waiting for SignChangeEvent from "
									+ target.getName() + " — no response received within "
									+ (timeoutTicks / 20.0) + "s.");
							HandlerList.unregisterAll(listener);
							restore(plugin, block, originalType, originalData);
							onResult.accept(new String[0]);
						}
					}
				}.runTaskLater(plugin, timeoutTicks);
			}
		}.runTaskLater(plugin, 1L);
	}

	private static void restore(Plugin plugin, Block block, Material originalType, BlockData originalData) {
		new BukkitRunnable() {
			@Override
			public void run() {
				block.setType(originalType, false);
				block.setBlockData(originalData, false);
			}
		}.runTask(plugin);
	}
}
