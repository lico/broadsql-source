package com.upandcoding.broadsql.controller.shell.commands.core.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.UserScriptLine;

/**
 * Appends a new line to a connection's login script: {@code ADD LOGIN SCRIPT LINE <connectionId>}.
 * The CLI equivalent of the config screen's "Login Scripts" tab, "add a line" action.
 *
 * <p>{@code connectionId} is mandatory and must name an active connection. Runs an interactive
 * wizard prompting for the SQL Command (mandatory), a Comment (optional) and Active {@code [y/n]},
 * followed by a {@code [y/n/c]} confirmation: {@code y} appends the line at the end of the script and
 * saves, {@code n} restarts the wizard from the top, anything else aborts without saving.
 */
public class CommandManageLoginScriptLineAdd extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandManageLoginScriptLineAdd.class);

	public CommandManageLoginScriptLineAdd() {
		super("ADD LOGIN SCRIPT LINE", "ADD LOG SC LI", "ADDLOGSCLI");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		String connectionId = CommandUtils.isValidArgs(args) ? args[0].trim() : null;
		if (!CommandUtils.requireActiveConnection(connectionId, console, getDatabaseConnectionsVault())) {
			return;
		}

		DatabaseDefinition placeholder = new DatabaseDefinition(connectionId);
		boolean repeat = true;
		while (repeat) {
			console.println("");
			console.println("Adding a new login script line for connection '" + connectionId + "'");
			String sqlCommand = console.inputField(placeholder, "SQL Command", "", false, false, false, null);
			String comment = console.inputField(placeholder, "Comment", "", true, false, false, null);
			String activeAnswer = console.inputField(placeholder, "Active [y/n]", "", false, false, true, null);
			boolean active = isYes(activeAnswer);

			String confirm = console.inputField(placeholder, "Save login script line [y/n/c]?", "", false, false, true, null);
			if (confirm.equalsIgnoreCase("y")) {
				repeat = false;
				List<UserScriptLine> lines = getDatabaseConnectionsVault().getUserScriptLines(getDatabaseConnectionsVault().getFileName(), getDatabaseConnectionsVault().getPassword(), connectionId);
				lines.add(new UserScriptLine(connectionId, sqlCommand, lines.size() + 1, active ? UserScriptLine.STATUS_ACTIVE : UserScriptLine.STATUS_INACTIVE, comment));
				getDatabaseConnectionsVault().saveUserScriptLines(connectionId, lines);
				console.println("");
				console.println("Login script line " + lines.size() + " added for connection '" + connectionId + "'");
			} else if (confirm.equalsIgnoreCase("n")) {
				repeat = true;
			} else {
				console.println("Operation aborted");
				repeat = false;
			}
		}
		console.println("");
	}

	private static boolean isYes(String answer) {
		return answer != null && (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"));
	}

	@Override
	public String getDescription() {
		return ("Appends a new line to an existing connection's login script");
	}

	@Override
	public String getArguments() {
		return "<connectionId> (mandatory) an existing, active connection ID";
	}

	@Override
	public String getExamples() {
		return "ADD LOGIN SCRIPT LINE MYDB01;";
	}
}
