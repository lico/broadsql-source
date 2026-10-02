package com.upandcoding.broadsql.dao.api;

import java.util.Map;
import java.util.TreeMap;

/**
 * Session-scoped BroadSQL variables set via {@code VAR name=value;} - SPRINT XT02A (URL-Native API
 * Execution), section 2.5/8: generic, not endpoint-owned, looked up case-insensitively by name, never
 * persisted (a deliberate, temporary override mechanism - section 2.5/7.3). Mirrors
 * {@link ApiSessionContextHolder}'s static-holder shape (single-worker-thread assumption, no
 * synchronization - same as every other piece of BroadSQL shell session state).
 */
public final class ApiSessionVariablesHolder {

	private static final Map<String, String> VALUES = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	private static final Map<String, String> DISPLAY_NAMES = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

	private ApiSessionVariablesHolder() {
	}

	public static void set(String name, String value) {
		VALUES.put(name, value);
		DISPLAY_NAMES.put(name, name);
	}

	/** {@code null} if no session VAR with this name (case-insensitive) is currently set. */
	public static String get(String name) {
		return name == null ? null : VALUES.get(name);
	}

	public static boolean isSet(String name) {
		return name != null && VALUES.containsKey(name);
	}

	/**
	 * Clears one session VAR by name (case-insensitive) - SPRINT XT02B, section 5: {@code VAR ...
	 * PERSIST} calls this after writing the same name to the current API environment, so the
	 * persisted value (the new single source of truth for that name) is what the normal resolution
	 * chain sees next, rather than a stale session override shadowing it. A no-op if the name was not
	 * set.
	 */
	public static void remove(String name) {
		if (name == null) {
			return;
		}
		VALUES.remove(name);
		DISPLAY_NAMES.remove(name);
	}

	public static void clearAll() {
		VALUES.clear();
		DISPLAY_NAMES.clear();
	}

	/** Every currently-set variable, keyed under the spelling last used to set it (display only - lookup is always case-insensitive). */
	public static Map<String, String> snapshot() {
		Map<String, String> result = new TreeMap<>();
		for (Map.Entry<String, String> e : VALUES.entrySet()) {
			result.put(DISPLAY_NAMES.get(e.getKey()), e.getValue());
		}
		return result;
	}
}
