package com.tabl.signtest;

/** One entry from config.yml's `mods:` section. */
record ModDefinition(String name, String key, DetectionMode mode) {
}
