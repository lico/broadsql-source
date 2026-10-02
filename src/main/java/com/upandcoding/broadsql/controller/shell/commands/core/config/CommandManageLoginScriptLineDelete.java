package com.upandcoding.broadsql.controller.shell.commands.core.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.UserScriptLine;

/**
 * Deletes a line from a connection's login script: {@code DEL LOGIN SCRIPT LINE <connectionId>
 * <lineNo>}.
 *
 * <p>{@code connectionId} is mandatory and must name an active connection; {@code lineNo} is the
 * 1-based line number shown by {@code SHOW LOGIN SCRIPT} and must be within range. Asks for a
 * {@code [y/n]} confirmation before proceeding; only {@code y}/{@code yes} deletes, anything else
 * aborts. Every line after the deleted one is renumbered up by one, exactly like the config screen's
 * "Login Scripts" tab.
 */
public class CommandManageLoginScriptLineDelete extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandManageLoginScriptLineDelete.class);

	public CommandManageLoginScriptLineDelete() {
		super("DEL LOGIN SCRIPT LINE", "DEL LOG SC LI", "DELLOGSCLI");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		String connectionId = CommandUtils.isValidArgs(args) ? args[0].trim() : null;
		if (!CommandUtils.requireActiveConnection(connectionId, console, getDatabaseConnectionsVault())) {
			return;
		}
		if (args.length < 2) {
			console.error(BroadSQLErrorMessages.ERR_LOGINSCRIPT_02);
			return;
		}
		Integer lineNo = CommandUtils.parseLineNumber(args[1], console);
		if (lineNo == null) {
			return;
		}

		List<UserScriptLine> lines = getDatabaseConnectionsVault().getUserScriptLines(getDatabaseConnectionsVault().getFileName(), getDatabaseConnectionsVault().getPassword(), connectionId);
		if (lineNo > lines.size()) {
			console.error("No login script line " + lineNo + " for connection '" + connectionId + "' - only " + lines.size() + " line(s) exist");
			return;
		}

		String confirm = console.inputField(new DatabaseDefinition(connectionId), "Delete login script line " + lineNo + " for connection '" + connectionId + "' [y/n]?", "", false, false, true, null);
		if (confirm != null && (confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes"))) {
			lines.remove(lineNo - 1);
			getDatabaseConnectionsVault().saveUserScriptLines(connectionId, lines);
			console.println("Login script line " + lineNo + " deleted for connection '" + connectionId + "'");
		} else {
			console.println("Deletion aborted");
		}
	}

	@Override
	public String getDescription() {
		return ("Deletes a line from a connection's login script");
	}

	@Override
	public String getArguments() {
		return "<connectionId> <lineNo> (both mandatory) an existing, active connection ID, and the 1-based line number shown by SHOW LOGIN SCRIPT";
	}

	@Override
	public String getExamples() {
		return "DEL LOGIN SCRIPT LINE MYDB01 2;";
	}
}
