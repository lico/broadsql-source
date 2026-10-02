package com.upandcoding.broadsql.controller.shell.swing;

/**
 * The three mutually-exclusive views every Settings tab (Connections, Database Groups,
 * Environments) offers over its records (SPRINT 0911D, requirement 2) - {@link #ACTIVE} is always
 * the default. Shared across all three tabs so they present one consistent lifecycle vocabulary and
 * interaction model, per {@code docs/CLAUDE.md}'s non-negotiable UX principle 5.
 */
enum SettingsViewMode {
	ACTIVE, INACTIVE, ALL
}
