package com.upandcoding.broadsql.controller.shell.commands.core.config;

import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.UserScriptLine;

/**
 * Reorders a line of a connection's login script by one position: {@code MOVE LOGIN SCRIPT LINE
 * <connectionId> <lineNo> UP|DOWN} - the CLI equivalent of the config screen's "Login Scripts" tab
 * up/down buttons.
 *
 * <p>{@code connectionId} is mandatory and must name an active connection; {@code lineNo} is the
 * 1-based line number shown by {@code SHOW LOGIN SCRIPT} and must be within range; {@code UP}/{@code
 * DOWN} is mandatory. Moving the first line {@code UP}, or the last line {@code DOWN}, is refused
 * with a clear message rather than silently doing nothing. Unlike the other login-script line
 * commands, this one is not interactive and asks for no confirmation - reordering is non-destructive
 * and immediately reversible by moving the line back.
 */
public class CommandManageLoginScriptLineMove extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandManageLoginScriptLineMove.class);

	public CommandManageLoginScriptLineMove() {
		super("MOVE LOGIN SCRIPT LINE", "MOVE LOG SC LI", "MOVLOGSCLI");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		String connectionId = CommandUtils.isValidArgs(args) ? args[0].trim() : null;
		if (!CommandUtils.requireActiveConnection(connectionId, console, getDatabaseConnectionsVault())) {
			return;
		}
		if (args.length < 3) {
			console.error(BroadSQLErrorMessages.ERR_LOGINSCRIPT_02);
			return;
		}
		Integer lineNo = CommandUtils.parseLineNumber(args[1], console);
		if (lineNo == null) {
			return;
		}
		String direction = args[2].trim();
		boolean up = "UP".equalsIgnoreCase(direction);
		boolean down = "DOWN".equalsIgnoreCase(direction);
		if (!up && !down) {
			console.error("Direction must be UP or DOWN");
			return;
		}

		List<UserScriptLine> lines = getDatabaseConnectionsVault().getUserScriptLines(getDatabaseConnectionsVault().getFileName(), getDatabaseConnectionsVault().getPassword(), connectionId);
		if (lineNo > lines.size()) {
			console.error("No login script line " + lineNo + " for connection '" + connectionId + "' - only " + lines.size() + " line(s) exist");
			return;
		}
		if (up && lineNo == 1) {
			console.error("Login script line " + lineNo + " is already first");
			return;
		}
		if (down && lineNo == lines.size()) {
			console.error("Login script line " + lineNo + " is already last");
			return;
		}

		int targetIndex = up ? lineNo - 2 : lineNo;
		Collections.swap(lines, lineNo - 1, targetIndex);
		getDatabaseConnectionsVault().saveUserScriptLines(connectionId, lines);
		console.println("Login script line " + lineNo + " moved " + (up ? "up" : "down") + " for connection '" + connectionId + "'");
	}

	@Override
	public String getDescription() {
		return ("Moves a line of a connection's login script up or down by one position");
	}

	@Override
	public String getArguments() {
		return "<connectionId> <lineNo> <UP|DOWN> (all mandatory) an existing, active connection ID, the 1-based line number shown by SHOW LOGIN SCRIPT, and the direction";
	}

	@Override
	public String getExamples() {
		return "MOVE LOGIN SCRIPT LINE MYDB01 2 UP;";
	}
}
