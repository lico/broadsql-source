package com.upandcoding.broadsql.controller.shell.commands.core.export;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.core.script.ScriptStatements;
import com.upandcoding.broadsql.controller.shell.commands.listsource.ClipboardAccess;
import com.upandcoding.broadsql.controller.shell.scripts.PreparedSql;
import com.upandcoding.broadsql.controller.shell.scripts.SqlReferences;
import com.upandcoding.broadsql.dao.JdbcBinder;
import com.upandcoding.broadsql.dao.LastApiExecutionResult;
import com.upandcoding.broadsql.dao.LastCopyableResult;
import com.upandcoding.broadsql.dao.LastCopyableResultHolder;
import com.upandcoding.broadsql.dao.api.tabular.ApiResultMaterializer;
import com.upandcoding.broadsql.dao.api.tabular.MaterializedApiResult;
import com.upandcoding.broadsql.dao.pull.text.PullToTextExporter;

/**
 * {@code COPY RESULT}: puts the last result held in memory on the system clipboard as tab-separated
 * text, ready to paste straight into Excel/Calc; see docs/TODO.md ("Spreadsheet integration") and
 * docs/TECHNICAL_CHANGE.md for why this exists (part C of the Excel/list-source quick-wins batch).
 *
 * <p>There are two possible sources: a SQL {@code lastSQLQuery} (the same one {@code /} replays and
 * {@code SHOW QUERY} displays, not a cached result set, since BroadSQL does not keep one around after a
 * query finishes, so it is re-run here), or the last {@code RUN} result
 * (materialized into a real {@link ResultSet} via {@link ApiResultMaterializer}, the exact same reusable
 * representation {@code PULL API RESULT TO ...} already consumes; see that class's own Javadoc).
 * {@code COPY RESULT} always consumes whichever of the two actually ran most recently in this session
 * (SPRINT XT02B acceptance correction, item 6), never a hard-coded SQL-first/API-fallback priority.
 * {@link LastCopyableResultHolder} resolves this: it is updated at the exact two points a SQL statement
 * or a successful {@code RUN} completes, so it always names the true winner regardless of execution
 * order; see that class's own Javadoc for why this is correct without comparing a timestamp or
 * sequence number. Takes no arguments; reports "No query in memory" if neither source has ever produced
 * a result yet in this session.
 *
 * <p>Both sources reuse {@link PullToTextExporter#renderTabSeparated} for the actual formatting, so the
 * clipboard's content is byte-for-byte the same table {@code PULL ... AS TXT} would write to a file:
 * same {@code NULL}-as-blank-field, same per-type value formatting, same RFC 4180-style quoting for a
 * value containing a tab/quote/newline (without which a value containing a tab would shift every
 * column after it when pasted).
 *
 * <p>Clipboard access goes through {@link ClipboardAccess}, shared with {@code <@clipboard>}'s read
 * side - same retry-on-transient-lock behavior, same clear error for a headless session.
 */
public class CommandCopyResult extends Command {

	public CommandCopyResult() {
		super("COPY RESULT");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		LastCopyableResult latest = LastCopyableResultHolder.get();
		if (latest == null) {
			console.println("No query in memory");
			return;
		}

		if (latest.getSource() == LastCopyableResult.Source.API) {
			copyFromApiResult(latest.getApiResult());
		} else {
			copyFromSql(latest.getSqlQuery());
		}
	}

	private void copyFromSql(String sqlQuery) throws BroadSQLException {
		Statement sourceStatement = null;
		ResultSet sourceResults = null;
		try {
			Connection sourceConnection = sqlDatabase.getDirectConnection();
			// SPRINT 0110A: the stored query's ${name} references are resolved again with the current values (spec 18.6)
			PreparedSql prepared = SqlReferences.prepare(sqlQuery, ScriptStatements.variables(this), sqlDatabase::isPgJdbc);
			if (prepared == null) {
				sourceStatement = sourceConnection.createStatement();
				sourceResults = sourceStatement.executeQuery(sqlQuery);
			} else {
				PreparedStatement preparedStatement = sourceConnection.prepareStatement(prepared.getJdbcText());
				sourceStatement = preparedStatement;
				JdbcBinder.bindAll(preparedStatement, prepared.getBinds());
				sourceResults = preparedStatement.executeQuery();
			}

			PullToTextExporter.RenderedTable rendered = new PullToTextExporter().renderTabSeparated(sourceResults);
			ClipboardAccess.writeText(rendered.text());

			console.println(rendered.rowCount() + " row(s) copied to the clipboard - paste directly into Excel/Calc.");
			console.println("");

		} catch (SQLException e) {
			throw new BroadSQLException(e);
		} finally {
			try {
				if (sourceResults != null) {
					sourceResults.close();
				}
				if (sourceStatement != null) {
					sourceStatement.close();
				}
			} catch (SQLException e) {
				throw new BroadSQLException(e);
			}
		}
	}

	private void copyFromApiResult(LastApiExecutionResult apiResult) throws BroadSQLException {
		try (MaterializedApiResult materialized = ApiResultMaterializer.materialize(apiResult)) {
			PullToTextExporter.RenderedTable rendered = new PullToTextExporter().renderTabSeparated(materialized.getResultSet());
			ClipboardAccess.writeText(rendered.text());

			console.println(rendered.rowCount() + " row(s) copied to the clipboard - paste directly into Excel/Calc.");
			console.println("");
		} catch (SQLException e) {
			throw new BroadSQLException(e);
		}
	}

	@Override
	public String getDescription() {
		return ("Copies the most recently produced result, SQL query or RUN, whichever ran last, to the system clipboard as tab-separated text, ready to paste into Excel/Calc");
	}

	@Override
	public String getArguments() {
		return "";
	}

	@Override
	public String getExamples() {
		return "SELECT * FROM CUSTOMER;\n\tCOPY RESULT;\n\tRUN /api/customer/123;\n\tCOPY RESULT;";
	}
}
