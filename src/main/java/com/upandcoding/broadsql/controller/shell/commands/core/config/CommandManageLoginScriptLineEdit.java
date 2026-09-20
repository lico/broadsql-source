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
 * Edits an existing line of a connection's login script: {@code EDIT LOGIN SCRIPT LINE
 * <connectionId> <lineNo>}.
 *
 * <p>{@code connectionId} is mandatory and must name an active connection; {@code lineNo} is the
 * 1-based line number shown by {@code SHOW LOGIN SCRIPT} and must be within range. Runs an
 * interactive wizard pre-filled with that line's SQL Command, Comment and Active flag, followed by a
 * {@code [y/n/c]} confirmation: {@code y} saves, {@code n} restarts the wizard from the top, anything
 * else aborts without saving. The line's position in the script is unchanged - use {@code MOVE LOGIN
 * SCRIPT LINE} to reorder.
 */
public class CommandManageLoginScriptLineEdit extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandManageLoginScriptLineEdit.class);

	public CommandManageLoginScriptLineEdit() {
		super("EDIT LOGIN SCRIPT LINE", "EDIT LOG SC LI", "EDLOGSCLI");
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
		UserScriptLine current = lines.get(lineNo - 1);

		DatabaseDefinition placeholder = new DatabaseDefinition(connectionId);
		boolean repeat = true;
		while (repeat) {
			console.println("");
			console.println("Editing login script line " + lineNo + " for connection '" + connectionId + "'");
			String sqlCommand = console.inputField(placeholder, "SQL Command", current.getSqlCommand(), false, false, false, null);
			String comment = console.inputField(placeholder, "Comment", current.getSqlComment(), true, false, false, null);
			String activeAnswer = console.inputField(placeholder, "Active [y/n] (currently " + (current.isActive() ? "y" : "n") + ")", "", false, false, true, null);
			boolean active = isYes(activeAnswer);

			String confirm = console.inputField(placeholder, "Save login script line [y/n/c]?", "", false, false, true, null);
			if (confirm.equalsIgnoreCase("y")) {
				repeat = false;
				lines.set(lineNo - 1, new UserScriptLine(connectionId, sqlCommand, lineNo, active ? UserScriptLine.STATUS_ACTIVE : UserScriptLine.STATUS_INACTIVE, comment));
				getDatabaseConnectionsVault().saveUserScriptLines(connectionId, lines);
				console.println("");
				console.println("Login script line " + lineNo + " successfully saved for connection '" + connectionId + "'");
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
		return ("Edits an existing line of a connection's login script");
	}

	@Override
	public String getArguments() {
		return "<connectionId> <lineNo> (both mandatory) an existing, active connection ID, and the 1-based line number shown by SHOW LOGIN SCRIPT";
	}

	@Override
	public String getExamples() {
		return "EDIT LOGIN SCRIPT LINE MYDB01 2;";
	}
}
