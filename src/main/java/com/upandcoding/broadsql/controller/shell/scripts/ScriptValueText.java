package com.upandcoding.broadsql.controller.shell.scripts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatter;

/**
 * SPRINT 0110A: how SQL scripting values are shown (spec sections 7.8, 7.9 and 10.6). Display only: binding
 * never goes through text.
 */
public final class ScriptValueText {

	/** Display truncation of routine confirmation and bound-value lines (never of stored values or ECHO). */
	public static final int DISPLAY_LIMIT = 200;

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	private ScriptValueText() {
	}

	/** The display rendering of spec section 7.8 ({@code ECHO} interpolation): NULL is {@code NULL}, strings as stored. */
	public static String render(ScriptValue value) {
		if (value == null || value.isNull()) {
			return "NULL";
		}
		Object v = value.getValue();
		if (v instanceof BigDecimal) {
			return ((BigDecimal) v).toPlainString();
		}
		if (v instanceof LocalDate) {
			return ((LocalDate) v).format(DATE);
		}
		if (v instanceof LocalTime) {
			return time((LocalTime) v);
		}
		if (v instanceof LocalDateTime) {
			LocalDateTime t = (LocalDateTime) v;
			return t.toLocalDate().format(DATE) + " " + time(t.toLocalTime());
		}
		if (v instanceof OffsetTime) {
			OffsetTime t = (OffsetTime) v;
			return time(t.toLocalTime()) + t.getOffset().getId();
		}
		if (v instanceof OffsetDateTime) {
			OffsetDateTime t = (OffsetDateTime) v;
			return t.toLocalDate().format(DATE) + " " + time(t.toLocalTime()) + t.getOffset().getId();
		}
		return String.valueOf(v);
	}

	/**
	 * The value as {@code SHOW SCRIPT VARIABLES}, confirmation lines and bound-value lines show it: a string
	 * single-quoted with {@code ''} doubling (so the string {@code 'NULL'} is told apart from NULL), every other
	 * value as {@link #render}.
	 */
	public static String listing(ScriptValue value) {
		if (value != null && value.getValue() instanceof String) {
			return "'" + ((String) value.getValue()).replace("'", "''") + "'";
		}
		return render(value);
	}

	/** {@code HH:mm:ss}, plus the fractional seconds only when non-zero, trailing zeros removed. */
	static String time(LocalTime value) {
		String text = value.format(TIME);
		int nanos = value.getNano();
		if (nanos == 0) {
			return text;
		}
		String fraction = String.format("%09d", nanos);
		int end = fraction.length();
		while (end > 0 && fraction.charAt(end - 1) == '0') {
			end--;
		}
		return text + "." + fraction.substring(0, end);
	}

	/**
	 * Control-character sanitization (spec section 10.6, security audit SEC-005): every character in U+0000 to
	 * U+001F except TAB and LF, U+007F, and U+0080 to U+009F becomes {@code ?}; CR is removed.
	 */
	public static String sanitize(String text) {
		if (text == null) {
			return null;
		}
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\r') {
				continue;
			}
			if (c == '\t' || c == '\n') {
				out.append(c);
			} else if (c <= 0x1F || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
				out.append('?');
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	/** {@code text} cut to {@link #DISPLAY_LIMIT} characters followed by {@code ...} when longer (display only). */
	public static String truncate(String text) {
		if (text == null || text.length() <= DISPLAY_LIMIT) {
			return text;
		}
		return text.substring(0, DISPLAY_LIMIT) + "...";
	}

	/** The routine line {@code <name> = <value> (<type>)} of a successful assignment, sanitized and truncated. */
	public static String confirmation(String name, ScriptValue value) {
		return sanitize(name + " = " + truncate(listing(value)) + " (" + value.getTypeName() + ")");
	}
}
