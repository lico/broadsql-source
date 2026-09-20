package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private String getFormattedCell(String content, int length) {
		String result = content;
		if (content == null || content.trim().equals("")) {
			result = "";
		}
		if ("-".equalsIgnoreCase(result.trim())) {
			result = StringUtils.leftPad("", length, "-");
		} else {
			result = StringUtils.rightPad(result, length, " ");
		}
		return (result);
	}//getFormattedCell

	private String getSeparatorRow() {
		char sep = consoleSettings.getOnScreenSeparator();
		return getFormattedCell("-", 4) + sep + getFormattedCell("-", 8) + sep + getFormattedCell("-", 60) + sep + getFormattedCell("-", 40) + sep;
	}

	private String getHeaderRow() {
		char sep = consoleSettings.getOnScreenSeparator();
		return getFormattedCell("#", 4) + sep + getFormattedCell("ACTIVE", 8) + sep + getFormattedCell("SQL COMMAND", 60) + sep + getFormattedCell("COMMENT", 40) + sep;
	}

	private String getRow(int lineNo, UserScriptLine line) {
		char sep = consoleSettings.getOnScreenSeparator();
		return getFormattedCell(String.valueOf(lineNo), 4) + sep + getFormattedCell(line.isActive() ? "y" : "n", 8) + sep + getFormattedCell(line.getSqlCommand(), 60) + sep
				+ getFormattedCell(line.getSqlComment(), 40) + sep;
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

		console.println(getSeparatorRow());
		console.println(getHeaderRow());
		console.println(getSeparatorRow());
		for (int i = 0; i < lines.size(); i++) {
			console.println(getRow(i + 1, lines.get(i)));
		}
		console.println(getSeparatorRow());
		console.println("");
		console.println("" + lines.size() + " login script line(s) found for connection '" + connectionId + "'");
		console.println("");
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
