package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.shell.output.TableBorders;
import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.UserScriptLine;

/**
 * Lists a connection's login script - the SQL statements run automatically whenever it connects:
 * {@code SHOW LOGIN SCRIPT <connectionId>}. The CLI equivalent of the config screen's "Login
 * Scripts" tab (read side).
 *
 * <p>{@code connectionId} is mandatory and must name an active connection - a distinct message is
 * shown when it names an inactive one. Prints every line (active and inactive alike), in the order
 * they run, numbered from 1 - that same number is what {@code ADD}/{@code EDIT}/{@code DEL}/{@code
 * MOVE LOGIN SCRIPT LINE} address as {@code <lineNo>}.
 */
public class CommandShowLoginScript extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandShowLoginScript.class);

	public CommandShowLoginScript() {
		super("SHOW LOGIN SCRIPT", "SH LOG SC", "SHLOGSC");
	}

	private static final int[] WIDTHS = { 4, 8, 60, 40 };

	/** A line of this command's table, through the shared table format (TableBorders), with the ScreenSeparator setting. */
	private String line(String... cells) {
		char sep = consoleSettings.getOnScreenSeparator();
		return cells.length == 0 ? TableBorders.separator(WIDTHS, 0, sep) : TableBorders.row(Arrays.asList(cells), WIDTHS, 0, sep);
	}

	private String getSeparatorRow() {
		return line();
	}

	private String getHeaderRow() {
		return line("#", "ACTIVE", "SQL COMMAND", "COMMENT");
	}

	private String getRow(int lineNo, UserScriptLine line) {
		return line(String.valueOf(lineNo), line.isActive() ? "y" : "n", line.getSqlCommand(), line.getSqlComment());
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		if (!CommandUtils.isValidArgs(args)) {
			console.error(BroadSQLErrorMessages.ERR_CONN_01);
			return;
		}
		String connectionId = args[0].trim();

		if (!getDatabaseConnectionsVault().contains(connectionId)) {
			if (getDatabaseConnectionsVault().isInactiveConnection(connectionId)) {
				console.error(CommandUtils.inactiveConnectionMessage(connectionId));
			} else {
				console.error(BroadSQLErrorMessages.ERR_CONN_01);
			}
			return;
		}

		List<UserScriptLine> lines = getDatabaseConnectionsVault().getUserScriptLines(getDatabaseConnectionsVault().getFileName(), getDatabaseConnectionsVault().getPassword(), connectionId);

		console.writeln(getSeparatorRow());
		console.writeln(getHeaderRow());
		console.writeln(getSeparatorRow());
		for (int i = 0; i < lines.size(); i++) {
			console.writeln(getRow(i + 1, lines.get(i)));
		}
		console.writeln(getSeparatorRow());
		console.writeln("");
		console.writeln("" + lines.size() + " login script line(s) found for connection '" + connectionId + "'");
		console.writeln("");
	}

	@Override
	public String getDescription() {
		return ("Displays the login script of an existing database connection");
	}

	@Override
	public String getArguments() {
		return "<connectionId> (mandatory) an existing, active connection ID";
	}

	@Override
	public String getExamples() {
		return "SHOW LOGIN SCRIPT MYDB01;";
	}
}
