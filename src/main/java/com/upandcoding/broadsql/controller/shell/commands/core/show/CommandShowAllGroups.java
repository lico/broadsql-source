package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.shell.output.TableBorders;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.dao.model.DatabaseGroupDefinition;

/**
 * Lists every configured Database Group: {@code SHOW ALL GROUPS} (docs/CONNECTION_MODEL.md §10.1).
 *
 * <p>Prints one row per Database Group (ID, Description, Active), active and inactive alike, sorted
 * by ID, with a trailing count - the Database Group equivalent of {@link CommandShowAllConnections},
 * using the exact same top/bottom dashed-separator table layout.
 */
public class CommandShowAllGroups extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandShowAllGroups.class);

	public CommandShowAllGroups() {
		super("SHOW ALL GROUPS", "SHALGR");
	}

	private static final int[] WIDTHS = { 15, 45, 8 };

	/** A line of this command's table, through the shared table format (TableBorders), with the ScreenSeparator setting. */
	private String line(String... cells) {
		char sep = consoleSettings.getOnScreenSeparator();
		return cells.length == 0 ? TableBorders.separator(WIDTHS, 0, sep) : TableBorders.row(Arrays.asList(cells), WIDTHS, 0, sep);
	}

	private String getFormattedRow(DatabaseGroupDefinition group, String marker) {
		if (group == null && marker == null) {
			return line("ID", "DESCRIPTION", "ACTIVE");
		}
		if (group != null) {
			return line(group.getId(), group.getDescr(), String.valueOf(group.isActive()));
		}
		return line();
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		List<DatabaseGroupDefinition> groups = getDatabaseConnectionsVault().getGroupDetails();

		console.writeln(getFormattedRow(null, "-"));
		console.writeln(getFormattedRow(null, null));
		console.writeln(getFormattedRow(null, "-"));
		for (DatabaseGroupDefinition group : groups) {
			console.writeln(getFormattedRow(group, null));
		}
		console.writeln(getFormattedRow(null, "-"));
		console.writeln("");
		console.writeln("" + groups.size() + " database group(s) found");
		console.writeln("");
	}

	@Override
	public String getDescription() {
		return ("Shows all configured Database Groups");
	}

	@Override
	public String getArguments() {
		return "none";
	}

	@Override
	public String getExamples() {
		return "SHOW ALL GROUPS;";
	}
}
