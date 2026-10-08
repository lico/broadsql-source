package com.upandcoding.broadsql.controller.shell.repeat;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * GitHub #196: a {@code REPEAT} duration ({@code EVERY 10s}, {@code FOR 30m}): a positive whole number immediately
 * followed by one unit, {@code s} (seconds), {@code m} (minutes) or {@code h} (hours), case-insensitive. Nothing else
 * is accepted: no space between the number and the unit, no milliseconds, no decimals, no compound value such as
 * {@code 1h30m} (write {@code 90m}), no days (write {@code 24h}), no zero and no sign. The smallest duration is
 * therefore one second. Each refused form gets its own explanation, always followed by {@link #ACCEPTED}.
 */
public final class RepeatDuration {

	/** The rule, appended to every duration error. */
	public static final String ACCEPTED = "A duration is a positive whole number immediately followed by s (seconds), m (minutes) or h (hours), "
			+ "for example 10s, 5m or 2h.";

	/** Generous enough for any monitoring use, small enough that seconds never overflow. */
	private static final long MAX_VALUE = 1_000_000_000L;

	private static final Pattern VALID = Pattern.compile("(\\d+)([smhSMH])");
	private static final Pattern MILLISECONDS = Pattern.compile("[+-]?\\d+(\\.\\d+)?ms", Pattern.CASE_INSENSITIVE);
	private static final Pattern DECIMAL = Pattern.compile("[+-]?\\d*[.,]\\d+[a-z]*", Pattern.CASE_INSENSITIVE);
	private static final Pattern COMPOUND = Pattern.compile("(\\d+[a-z]+){2,}", Pattern.CASE_INSENSITIVE);
	private static final Pattern DAYS = Pattern.compile("\\d+d", Pattern.CASE_INSENSITIVE);
	private static final Pattern NEGATIVE = Pattern.compile("-\\d+[a-z]*", Pattern.CASE_INSENSITIVE);
	private static final Pattern NUMBER_ONLY = Pattern.compile("[+-]?\\d+");

	private final long value;
	private final char unit;

	private RepeatDuration(long value, char unit) {
		this.value = value;
		this.unit = unit;
	}

	/**
	 * @param text   the duration as written, e.g. {@code 10s}
	 * @param clause the clause it belongs to ({@code EVERY} or {@code FOR}), named in the errors
	 */
	public static RepeatDuration parse(String text, String clause) throws BroadSQLException {
		if (text == null || text.isBlank()) {
			throw new BroadSQLException(clause + " needs a duration. " + ACCEPTED);
		}
		String t = text.trim();
		Matcher valid = VALID.matcher(t);
		if (valid.matches()) {
			String digits = valid.group(1);
			long number;
			try {
				number = digits.length() > 10 ? Long.MAX_VALUE : Long.parseLong(digits);
			} catch (NumberFormatException e) {
				number = Long.MAX_VALUE;
			}
			if (number == 0) {
				throw new BroadSQLException("Invalid " + clause + " duration '" + t + "': it must be greater than zero (the minimum is 1s). " + ACCEPTED);
			}
			if (number > MAX_VALUE) {
				throw new BroadSQLException("Invalid " + clause + " duration '" + t + "': the number is too large. " + ACCEPTED);
			}
			return new RepeatDuration(number, Character.toLowerCase(valid.group(2).charAt(0)));
		}
		throw new BroadSQLException("Invalid " + clause + " duration '" + t + "': " + reason(t) + " " + ACCEPTED);
	}

	private static String reason(String t) {
		if (MILLISECONDS.matcher(t).matches()) {
			return "milliseconds are not supported (the minimum is 1s).";
		}
		if (NEGATIVE.matcher(t).matches()) {
			return "a duration cannot be negative.";
		}
		if (DECIMAL.matcher(t).matches()) {
			return "decimal values are not supported (write 90s rather than 1.5m).";
		}
		if (DAYS.matcher(t).matches()) {
			return "days are not supported (write 24h for one day).";
		}
		if (COMPOUND.matcher(t).matches()) {
			return "compound durations are not supported (write 90m rather than 1h30m).";
		}
		if (NUMBER_ONLY.matcher(t).matches()) {
			return "the unit is missing, and it must follow the number without a space (write " + t.replace("+", "") + "s, not " + t + " s).";
		}
		return "this is not a duration.";
	}

	/** @return the duration in seconds */
	public long seconds() {
		switch (unit) {
			case 'h':
				return value * 3600;
			case 'm':
				return value * 60;
			default:
				return value;
		}
	}

	/** @return the duration in milliseconds */
	public long millis() {
		return seconds() * 1000;
	}

	/** The canonical form, lowercase unit: {@code 10s}, {@code 5m}, {@code 2h}. */
	@Override
	public String toString() {
		return value + String.valueOf(unit).toLowerCase(Locale.ROOT);
	}
}
