package com.upandcoding.broadsql.controller.shell.commands.core.sql;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.sql.SqlFormatResult;
import com.upandcoding.broadsql.controller.shell.sql.SqlFormatterService;

/**
 * {@code FORMAT;} - explicitly reformats the current SQL for readability (see
 * docs/SPRINT_0912A_EXPAND_FORMAT_SQL_ERRORS.md, section 5).
 *
 * <p>This is a transformation, not an execution command: it never runs the current SQL, only
 * reflows it. On success, the formatted SQL becomes the current SQL - {@code //}/{@code SHOW QUERY}
 * display it and {@code /} reruns it. On failure (the SQL cannot be tokenized safely - an
 * unterminated string literal, quoted identifier or comment), the current SQL is left
 * byte-for-byte unchanged and a concise reason is printed.
 *
 * <p>{@code FORMAT;} only ever changes whitespace and line breaks between tokens - it never
 * rewrites, reorders or drops the text of a string literal, comment (Oracle hints included),
 * quoted identifier, number or keyword, so it cannot change what the statement means. See
 * {@link SqlFormatterService} for why that guarantee holds unconditionally, and why no external
 * formatter library or regex-based approach was used.
 */
public class CommandFormat extends Command {

	public CommandFormat() {
		super("FORMAT");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		if (StringUtils.isBlank(this.lastSQLQuery)) {
			console.println("No query in memory to format.");
			return;
		}

		SqlFormatResult result = SqlFormatterService.format(this.lastSQLQuery);

		if (!result.isSupported()) {
			console.println(result.getReason());
			return;
		}

		this.lastSQLQuery = result.getFormattedSql();
		console.println(this.lastSQLQuery);
	}

	@Override
	public String getDescription() {
		return "Reformats the current SQL for readability without changing its meaning";
	}

	@Override
	public String getArguments() {
		return "";
	}

	@Override
	public String getExamples() {
		return "select a.id,a.name from customer a where a.status='ACTIVE';\nFORMAT;\n//";
	}
}
