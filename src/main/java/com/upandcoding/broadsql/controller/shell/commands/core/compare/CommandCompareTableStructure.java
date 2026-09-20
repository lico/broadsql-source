package com.upandcoding.broadsql.controller.shell.commands.core.compare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.compare.TableStructureComparer;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.metadata.ColumnMetadata;

/**
 * Compares the column structure of a table between the current connection and another connection:
 * {@code COMPARE TABLE STRUCTURE <table> WITH <connection>}.
 *
 * <p>{@code <connection>} must be a connection defined in the Connections Definition File (CDF); the
 * command opens its own, separate, short lived JDBC connection to it, the current connection is
 * never touched or replaced (see {@code PULL ... AS H2}, {@code CommandPull}, for the same design).
 *
 * <p>{@code <table>} may be schema qualified ({@code SCHEMA.TABLE}); when it is not, each side
 * defaults independently to its own current schema, then the name is tried as typed, upper cased
 * and lower cased on each side independently, the same case fallback {@code DESCR} uses, since two
 * different database platforms may fold an unquoted identifier to a different case.
 *
 * <p>The report lists columns present on only one side, columns present on both sides but with a
 * different type, size, decimal digits or nullability, and a count of columns that match exactly.
 *
 * <p>Fails with an error if {@code <table>} does not exist on either side, or if {@code <connection>}
 * is not defined in the CDF.
 */
public class CommandCompareTableStructure extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandCompareTableStructure.class);

	public CommandCompareTableStructure() {
		super("COMPARE TABLE STRUCTURE", "CM TA ST", "CMTAST");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);
		if (!CommandUtils.isValidArgs(args) || args.length != 3 || !"WITH".equalsIgnoreCase(args[1])) {
			throw new BroadSQLException("COMPARE TABLE STRUCTURE requires <table> WITH <connection>, "
					+ "e.g. COMPARE TABLE STRUCTURE CUSTOMER WITH B");
		}
		String tableName = args[0];
		String connectionName = args[2];

		if (!getDatabaseConnectionsVault().contains(connectionName)) {
			if (getDatabaseConnectionsVault().isInactiveConnection(connectionName)) {
				throw new BroadSQLException(CommandUtils.inactiveConnectionMessage(connectionName));
			}
			throw new BroadSQLException("Connection '" + connectionName + "' not defined");
		}
		DatabaseDefinition target = getDatabaseConnectionsVault().getDatabaseConnection(connectionName);

		TableStructureComparer.Result result = new TableStructureComparer().compare(sqlDatabase, target, connectionName, tableName);
		printReport(tableName, connectionName, result);
	}

	private void printReport(String tableName, String connectionName, TableStructureComparer.Result result) {
		if (result.isIdentical()) {
			console.writeln("Table '" + tableName + "' has an identical structure on this connection and '" + connectionName + "'.");
			return;
		}

		console.writeln("Comparing structure of table '" + tableName + "' between this connection and '" + connectionName + "'");
		console.writeln("");

		if (!result.getOnlyOnSource().isEmpty()) {
			console.writeln("Columns only on this connection (not on '" + connectionName + "'):");
			for (ColumnMetadata column : result.getOnlyOnSource()) {
				console.writeln("  " + column.getName());
			}
			console.writeln("");
		}

		if (!result.getOnlyOnTarget().isEmpty()) {
			console.writeln("Columns only on '" + connectionName + "' (not on this connection):");
			for (ColumnMetadata column : result.getOnlyOnTarget()) {
				console.writeln("  " + column.getName());
			}
			console.writeln("");
		}

		if (!result.getDiffering().isEmpty()) {
			console.writeln("Columns with a different definition:");
			for (TableStructureComparer.ColumnDifference diff : result.getDiffering()) {
				console.writeln("  " + diff.getSource().getName() + ": this connection = " + formatDefinition(diff.getSource())
						+ ", '" + connectionName + "' = " + formatDefinition(diff.getTarget()));
			}
			console.writeln("");
		}

		console.writeln("Structure is identical for " + result.getIdenticalCount() + " column(s).");
	}

	private String formatDefinition(ColumnMetadata column) {
		String size = column.getDecimalDigit() > 0
				? column.getColumnSize() + "," + column.getDecimalDigit()
				: String.valueOf(column.getColumnSize());
		String nullable = "NO".equalsIgnoreCase(column.getIsoNullable()) ? "NOT NULL" : "NULL";
		return column.getTypeName() + "(" + size + ") " + nullable;
	}

	@Override
	public String getDescription() {
		return ("Compares the column structure of a table between the current connection and another connection");
	}

	@Override
	public String getArguments() {
		return "<table> (mandatory) a table name, optionally schema-qualified; WITH <connection> (mandatory) a connection defined in the CDF";
	}

	@Override
	public String getExamples() {
		return "COMPARE TABLE STRUCTURE CUSTOMER WITH B;";
	}
}
