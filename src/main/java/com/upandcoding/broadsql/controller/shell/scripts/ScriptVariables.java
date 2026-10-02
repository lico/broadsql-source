package com.upandcoding.broadsql.controller.shell.scripts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SPRINT 0110A: the one SQL scripting variable namespace of a BroadSQL session (spec sections 7.3 to 7.6).
 * Owned by the session's {@code CommandInterpreter} (never static, never the database connection): the
 * prompt, every Script at every nesting level and every script argument read and write this same map.
 * Names are case-insensitive; the spelling of the most recent assignment is kept for display. Nothing is
 * copied, restored or discarded when a Script starts or ends.
 *
 * <p>Not thread-safe by design, like {@link ScriptContextStack}: statements run strictly one after another.
 */
public final class ScriptVariables {

	/** One variable: its display spelling and its value. */
	public record Entry(String name, ScriptValue value) {
	}

	private final Map<String, Entry> entries = new HashMap<>();

	/** Assigns (or replaces) {@code name}; the name must already be valid ({@link VariableNames#isValid}). */
	public void assign(String name, ScriptValue value) {
		if (!VariableNames.isValid(name)) {
			throw new IllegalArgumentException("Invalid variable name: " + name);
		}
		if (value == null) {
			throw new IllegalArgumentException("value");
		}
		entries.put(VariableNames.key(name), new Entry(name, value));
	}

	/** Assigns every entry of {@code values} (already validated and evaluated), in order. */
	public void assignAll(LinkedHashMap<String, ScriptValue> values) {
		for (Map.Entry<String, ScriptValue> entry : values.entrySet()) {
			assign(entry.getKey(), entry.getValue());
		}
	}

	public boolean isDefined(String name) {
		return name != null && entries.containsKey(VariableNames.key(name));
	}

	/** The value of {@code name}, {@code null} when undefined (a NULL variable is defined: its value object is never {@code null}). */
	public ScriptValue get(String name) {
		Entry entry = name == null ? null : entries.get(VariableNames.key(name));
		return entry == null ? null : entry.value();
	}

	/** The display spelling of {@code name} (the most recent assignment's), or {@code name} itself when undefined. */
	public String displayName(String name) {
		Entry entry = name == null ? null : entries.get(VariableNames.key(name));
		return entry == null ? name : entry.name();
	}

	public int size() {
		return entries.size();
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	/** Every variable, sorted by name case-insensitively (spec section 7.9). */
	public List<Entry> sorted() {
		List<Entry> list = new ArrayList<>(entries.values());
		list.sort(Comparator.comparing((Entry e) -> VariableNames.key(e.name())).thenComparing(Entry::name));
		return list;
	}
}
