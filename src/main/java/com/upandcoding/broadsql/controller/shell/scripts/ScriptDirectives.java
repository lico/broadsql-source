package com.upandcoding.broadsql.controller.shell.scripts;

import java.util.Locale;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * SPRINT 0110A: the grammar of the two execution-control statements, {@code ON ERROR STOP|CONTINUE} and
 * {@code OUTPUT QUIET|NORMAL} (spec sections 11.1 and 12.1), shared by their commands and by the Script
 * preflight, which validates every such statement before anything runs (spec 12.5: a misspelt control
 * directive would otherwise silently change the behavior of everything after it).
 */
public final class ScriptDirectives {

	public static final String ON_ERROR = "ON ERROR";
	public static final String OUTPUT = "OUTPUT";

	private ScriptDirectives() {
	}

	public static boolean isOnError(String statement) {
		return CommandUtils.startsWithKeyword(statement, ON_ERROR);
	}

	public static boolean isOutput(String statement) {
		return CommandUtils.startsWithKeyword(statement, OUTPUT);
	}

	/** The policy of an {@code ON ERROR} statement (the whole statement text). */
	public static ErrorPolicy parseOnError(String statement) throws BroadSQLException {
		String value = singleArgument(statement, ON_ERROR);
		if ("STOP".equals(value)) {
			return ErrorPolicy.STOP;
		}
		if ("CONTINUE".equals(value)) {
			return ErrorPolicy.CONTINUE;
		}
		throw new BroadSQLException("Invalid ON ERROR statement: use ON ERROR STOP or ON ERROR CONTINUE");
	}

	/** The mode of an {@code OUTPUT} statement (the whole statement text). */
	public static OutputMode parseOutput(String statement) throws BroadSQLException {
		String value = singleArgument(statement, OUTPUT);
		if ("QUIET".equals(value)) {
			return OutputMode.QUIET;
		}
		if ("NORMAL".equals(value)) {
			return OutputMode.NORMAL;
		}
		throw new BroadSQLException("Invalid OUTPUT statement: use OUTPUT QUIET or OUTPUT NORMAL");
	}

	/** The one upper-cased word after {@code keyword}, or {@code null} when there is none or more than one. */
	private static String singleArgument(String statement, String keyword) {
		String q = statement == null ? "" : statement.strip();
		int end = CommandUtils.keywordMatchEnd(q, keyword);
		if (end < 0) {
			return null;
		}
		String rest = q.substring(end).strip();
		if (rest.isEmpty() || rest.chars().anyMatch(Character::isWhitespace)) {
			return null;
		}
		return rest.toUpperCase(Locale.ROOT);
	}
}
