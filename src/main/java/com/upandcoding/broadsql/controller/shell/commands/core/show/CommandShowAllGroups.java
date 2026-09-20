package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private String getFormattedRow(DatabaseGroupDefinition group, String marker) {
		char sep = consoleSettings.getOnScreenSeparator();

		String id = getFormattedCell("-", 15);
		String descr = getFormattedCell("-", 45);
		String active = getFormattedCell("-", 8);

		if (group == null && marker == null) {
			id = getFormattedCell("ID", 15);
			descr = getFormattedCell("DESCRIPTION", 45);
			active = getFormattedCell("ACTIVE", 8);
		} else if (group != null) {
			id = getFormattedCell(group.getId(), 15);
			descr = getFormattedCell(group.getDescr(), 45);
			active = getFormattedCell(String.valueOf(group.isActive()), 8);
		}

		return id + sep + descr + sep + active + sep;
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		List<DatabaseGroupDefinition> groups = getDatabaseConnectionsVault().getGroupDetails();

		console.println(getFormattedRow(null, "-"));
		console.println(getFormattedRow(null, null));
		console.println(getFormattedRow(null, "-"));
		for (DatabaseGroupDefinition group : groups) {
			console.println(getFormattedRow(group, null));
		}
		console.println(getFormattedRow(null, "-"));
		console.println("");
		console.println("" + groups.size() + " database group(s) found");
		console.println("");
	}

	@Override
	public String getDescription() {
		return ("Show all configured Database Groups");
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
