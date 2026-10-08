package com.upandcoding.broadsql.controller.shell.commands.core.sql;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.sql.SqlWildcardExpander;
import com.upandcoding.broadsql.controller.shell.sql.WildcardExpansionResult;

/**
 * {@code EXPAND;} - explicitly rewrites the current SQL's {@code SELECT *} / {@code alias.*}
 * projection(s) into the real column names of the relation(s) they refer to, resolved from
 * database metadata (see docs/SPRINT_0912A_EXPAND_FORMAT_SQL_ERRORS.md, section 4).
 *
 * <p>This is a transformation, not an execution command: it never runs the current SQL, only
 * rewrites it. On success, the expanded SQL becomes the current SQL - {@code //}/{@code SHOW QUERY}
 * display it and {@code /} reruns it. On failure (unsupported shape, unresolvable relation,
 * ambiguous alias, no expandable {@code *} at all), the current SQL is left byte-for-byte unchanged
 * and a concise reason is printed - nothing is ever executed as a side effect.
 *
 * <p>Supported: {@code SELECT * FROM TABLE}, schema-qualified tables, {@code alias.*} with or
 * without {@code AS}, a naked {@code *} only when the query has exactly one relation (no
 * {@code JOIN}), and {@code alias.*} across a {@code JOIN} chain. Never touched:
 * {@code COUNT(*)}, arithmetic ({@code a*b}), and any {@code *} inside a string literal or comment
 * - the underlying scanner only ever considers a bare {@code *} or {@code alias.*} that forms an
 * entire projection item by itself. Not supported in this version: a derived table/subquery or CTE
 * as the {@code FROM} source, and a naked {@code *} over more than one relation (its result-column
 * order and duplicate-name handling cannot be reproduced reliably - qualify with {@code alias.*}
 * instead).
 */
public class CommandExpand extends Command {

	public CommandExpand() {
		super("EXPAND");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		if (StringUtils.isBlank(this.lastSQLQuery)) {
			console.println("No query in memory. EXPAND applies to a SELECT containing a resolvable * or alias.* projection.");
			return;
		}

		SqlWildcardExpander expander = new SqlWildcardExpander(this::resolveColumns);
		WildcardExpansionResult result = expander.expand(this.lastSQLQuery);

		if (!result.isSupported()) {
			console.println(result.getReason());
			return;
		}

		this.lastSQLQuery = result.getExpandedSql();
		console.println(this.lastSQLQuery);
	}

	private List<String> resolveColumns(String relationReference) throws BroadSQLException {
		ResultSetMetaData metaData = sqlDatabase.getMetaData(relationReference);
		if (metaData == null) {
			return null;
		}
		try {
			List<String> columns = new ArrayList<>();
			int count = metaData.getColumnCount();
			for (int i = 1; i <= count; i++) {
				columns.add(metaData.getColumnName(i));
			}
			return columns;
		} catch (java.sql.SQLException e) {
			throw new BroadSQLException(e);
		}
	}

	@Override
	public String getDescription() {
		return "Replaces the current SQL's SELECT * / alias.* projection with real column names from database metadata";
	}

	@Override
	public String getArguments() {
		return "";
	}

	@Override
	public String getExamples() {
		return "SELECT * FROM CUSTOMER;\nEXPAND;\n//";
	}
}
