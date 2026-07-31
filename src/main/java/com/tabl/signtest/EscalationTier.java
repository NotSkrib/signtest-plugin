package com.tabl.signtest;

/** One rung of the escalation ladder in config.yml's escalation.tiers list. */
final class EscalationTier {

	final String action; // "kick" or "ban"
	final String message;
	final long banDurationMinutes; // only used when action = "ban"; 0 = permanent

	EscalationTier(String action, String message, long banDurationMinutes) {
		this.action = action;
		this.message = message;
		this.banDurationMinutes = banDurationMinutes;
	}
}
