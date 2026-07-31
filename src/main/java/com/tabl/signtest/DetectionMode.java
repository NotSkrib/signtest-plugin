package com.tabl.signtest;

/**
 * Which text-component mechanism to probe a mod's key with. Both leak
 * through the same sign-edit echo, but a client (or a protection mod) can
 * block one without blocking the other, so a key that doesn't resolve one
 * way may still resolve the other.
 */
enum DetectionMode {
	/** Component.translatable(key, fallback) — resolves the key's display text. */
	TRANSLATE,
	/** Component.keybind(key) — resolves to the actual bound key/button. */
	KEYBIND
}
