package com.upandcoding.broadsql.dao.load;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.load.LoadStatement.ExecutionMode;

/**
 * Parses the arguments (already split by {@link com.upandcoding.broadsql.controller.shell.commands.Command#parseArgs})
 * of a {@code LOAD} or {@code BATCHLOAD} command line into a {@link LoadStatement}.
 *
 * <pre>
 * LOAD &lt;table&gt; &lt;file&gt; [PREVIEW | EXECUTE]
 * LOAD CREATE &lt;table&gt; &lt;file&gt; [PREVIEW | EXECUTE]   -- legacy form, kept for compatibility: CREATE already meant INSERT
 * LOAD UPDATE &lt;table&gt; &lt;file&gt; ...                  -- rejected outright, see below
 * </pre>
 *
 * <p>SPRINT 0912B is INSERT-only (section 7), so the {@code CREATE}/{@code UPDATE} mode keyword the
 * legacy {@code LOAD}/{@code BATCHLOAD} required is no longer a real choice - it is optional now
 * ({@code CREATE} means exactly what a bare {@code LOAD <table> <file>} already means), kept only so
 * an existing script written against the old 3-argument form keeps working unchanged (section 4.1:
 * "prefer the least disruptive evolution"). {@code UPDATE} cannot be mapped onto an INSERT-only
 * engine, so it is rejected with an explicit migration message rather than silently reinterpreted as
 * an insert (section 17) - row-level UPDATE/UPSERT is out of scope for this sprint (see
 * {@code docs/SQL_PRODUCTIVITY_AND_SAFE_LOAD.md}) and may return in a future one.
 */
public final class LoadCommandParser {

	private LoadCommandParser() {
	}

	public static LoadStatement parse(String[] args, String commandKeywordUsed) throws BroadSQLException {
		if (args == null || args.length < 2) {
			throw new BroadSQLException(commandKeywordUsed + " requires a table name and a file name, e.g. "
					+ commandKeywordUsed + " CUSTOMER customer.csv");
		}

		String[] rest = args;
		if ("UPDATE".equalsIgnoreCase(rest[0])) {
			throw new BroadSQLException(commandKeywordUsed + " UPDATE is no longer supported - " + commandKeywordUsed
					+ " now only performs INSERT (see docs/SQL_PRODUCTIVITY_AND_SAFE_LOAD.md); row-level UPDATE/UPSERT "
					+ "is planned as separate, future work. Use " + commandKeywordUsed + " <table> <file> for INSERT.");
		}
		if ("CREATE".equalsIgnoreCase(rest[0])) {
			rest = new String[args.length - 1];
			System.arraycopy(args, 1, rest, 0, rest.length);
		}

		if (rest.length < 2) {
			throw new BroadSQLException(commandKeywordUsed + " requires a table name and a file name, e.g. "
					+ commandKeywordUsed + " CUSTOMER customer.csv");
		}
		if (rest.length > 3) {
			throw new BroadSQLException("Unexpected extra arguments after " + commandKeywordUsed + " " + rest[0] + " " + rest[1]);
		}

		String tableName = rest[0];
		String fileName = rest[1];
		ExecutionMode mode = ExecutionMode.CONFIRM;

		if (rest.length == 3) {
			String trailing = rest[2];
			if ("PREVIEW".equalsIgnoreCase(trailing)) {
				mode = ExecutionMode.PREVIEW;
			} else if ("EXECUTE".equalsIgnoreCase(trailing)) {
				mode = ExecutionMode.EXECUTE;
			} else {
				throw new BroadSQLException("Unexpected trailing argument '" + trailing + "' after " + commandKeywordUsed
						+ " " + tableName + " " + fileName + " - only PREVIEW or EXECUTE is supported here.");
			}
		}

		return new LoadStatement(tableName, fileName, mode);
	}
}
