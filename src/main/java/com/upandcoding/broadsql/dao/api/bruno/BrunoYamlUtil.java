package com.upandcoding.broadsql.dao.api.bruno;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Null-safe accessors for the generic {@code Map}/{@code List} tree SnakeYAML produces when loading an
 * OpenCollection document with no POJO binding - see {@link BrunoCollectionImporter}'s class Javadoc for
 * why manual Map-walking was chosen over SnakeYAML's TypeDescription binding.
 */
final class BrunoYamlUtil {

	private BrunoYamlUtil() {
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> asMap(Object value) {
		return value instanceof Map ? (Map<String, Object>) value : null;
	}

	@SuppressWarnings("unchecked")
	static List<Object> asList(Object value) {
		if (value instanceof List) {
			return (List<Object>) value;
		}
		return Collections.emptyList();
	}

	static String asString(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	static boolean asBoolean(Object value, boolean defaultValue) {
		return value instanceof Boolean ? (Boolean) value : defaultValue;
	}

	static Integer asInteger(Object value) {
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		return null;
	}

	/** {@code root.get("config").get("environments")}, tolerant of any missing intermediate level. */
	static Object pathGet(Map<String, Object> root, String... path) {
		Object current = root;
		for (String segment : path) {
			Map<String, Object> map = asMap(current);
			if (map == null) {
				return null;
			}
			current = map.get(segment);
		}
		return current;
	}
}
