package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * Lists the connections that share a Database Group with a given connection:
 * {@code SHOW GROUP [connectionId] [environmentName]}.
 *
 * <p>{@code connectionId} is optional and defaults to the current connection. Its Database Group is
 * looked up, then every connection defined in the CDF with that same group is listed (ID, user,
 * name, type), grouped by environment, with a trailing count. {@code environmentName} is optional
 * and, if given, restricts the list to connections whose environment matches it exactly
 * (case-insensitive). This is the {@code SHOW GROUP} example of {@code docs/CONNECTION_MODEL.md}
 * §10.
 *
 * <p>A warning is shown instead if the connection doesn't exist or has no Database Group defined.
 *
 * <p>Phase 1 of the {@code docs/CONNECTION_MODEL.md} rework: this class replaces
 * {@code CommandShowEnvironments}, whose primary keyword ({@code SHOW ENVIRONMENTS}) had already come
 * to mean roughly what {@code docs/CONNECTION_MODEL.md} §10 specifies for {@code SHOW GROUP} - listing
 * every connection sharing the current one's Instance (now Database Group), grouped by Environment.
 * That keyword is kept, along with its other aliases ({@code SH ENV}/{@code SHENV}/
 * {@code SHOW ENVTS}), as backward-compatible aliases on this same command, per §11's explicit
 * allowance ("Backward-compatible aliases may be retained internally if required"): {@code SHOW
 * ENVIRONMENTS} the keyword is not the same thing as {@code SHOW ALL ENVIRONMENTS}
 * ({@link CommandShowAllEnvironments}, the new, unrelated command listing the global Environment
 * referential) - readers should not conflate the two on name alone.
 *
 * <p>Before that, this command had itself replaced {@code SHOW INSTANCES} (docs/TECHNICAL_CHANGE.md,
 * 2026-09-05, "SHOW INSTANCES replaced by SHOW ENVIRONMENTS"): that earlier command fixed the current
 * connection's Environment and listed every connection sharing it across every Instance - the wrong
 * axis once Instance/Environment held their correct, post-swap meaning. {@code SHOW INSTANCES} was
 * retired outright at the user's explicit request at the time, not kept as an alias.
 */
public class CommandShowGroup extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandShowGroup.class);

	public CommandShowGroup() {
		super("SHOW GROUP", "SHOW ENVIRONMENTS", "SH ENV", "SHENV", "SHOW ENVTS");
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

	private String getFormattedRow(DatabaseDefinition pl, String name) throws BroadSQLException {
		String result = "";
		char sep = consoleSettings.getOnScreenSeparator();

		String id = getFormattedCell("-", 15);
		String type = getFormattedCell("-", 15);
		String dbName = getFormattedCell("-", 45);
		String uName = getFormattedCell("-", 20);
		String environment = getFormattedCell("-", 12);

		if (pl == null && name == null) {
			id = getFormattedCell("ID", 15);
			type = getFormattedCell("TYPE", 15);
			dbName = getFormattedCell("NAME", 45);
			uName = getFormattedCell("USER", 20);
			environment = getFormattedCell("ENVIRONMENT", 12);

		} else if (pl != null && !name.trim().equals("")) {
			id = getFormattedCell(name, 15);
			type = getFormattedCell(pl.getDbType(), 15);
			dbName = getFormattedCell(pl.getDbName(), 45);
			uName = getFormattedCell(pl.getUserName(), 20);
			environment = getFormattedCell(pl.getEnvironment(), 12);

		}

		result = environment + sep + id + sep + uName + sep + dbName + sep + type + sep;

		return (result);
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		// Selected platform
		String platformID = null;
		if (CommandUtils.isValidArgs(args)) {
			platformID = args[0];
			platformID = platformID.trim();
			if (platformID.endsWith(";")) {
				platformID = StringUtils.substringBefore(platformID, ";");
			}
		} else {
			platformID = this.getPlatform();
		}

		// Selected environment
		String selEnvironment = null;
		if (CommandUtils.isValidArgs(args) && args.length > 1 && StringUtils.isNotBlank(args[1])) {
			selEnvironment = args[1].trim();
		}

		if (StringUtils.isNotBlank(platformID) && getDatabaseConnectionsVault().contains(platformID)) {
			// Database Group of selected platform
			DatabaseDefinition pl = getDatabaseConnectionsVault().getDatabaseConnection(platformID);
			if (pl != null) {
				String group = pl.getDatabaseGroup();
				if (StringUtils.isNotBlank(group)) {

					console.println("List of environments for Database Group '" + group + "':");

					console.println(getFormattedRow(null, "-"));
					console.println(getFormattedRow(null, null));
					console.println(getFormattedRow(null, "-"));

					int n = 0;
					Map<String, DatabaseDefinition> pltfrms = getDatabaseConnectionsVault().getDatabaseConnections();
					Set<String> keys = pltfrms.keySet();
					for (String key : keys) {
						DatabaseDefinition pltfrm = pltfrms.get(key);
						if (pltfrm != null && group.equalsIgnoreCase(pltfrm.getDatabaseGroup())) {
							if (StringUtils.isNotBlank(selEnvironment)) {
								if (selEnvironment.equalsIgnoreCase(pltfrm.getEnvironment())) {
									console.println(getFormattedRow(pltfrm, key));
									n++;
								}
							} else {
								console.println(getFormattedRow(pltfrm, key));
								n++;
							}
						}
					}

					console.println(getFormattedRow(null, "-"));
					console.println("");
					console.println("" + n + " database connections found");
					console.println("");
				} else {
					console.println("Current connection " + platformID + " has no Database Group defined", ShellConsole.MSG_WARN);
				}
			} else {
				console.println("Current connection " + platformID + " does not exist", ShellConsole.MSG_WARN);
			}
		} else if (StringUtils.isNotBlank(platformID) && getDatabaseConnectionsVault().isInactiveConnection(platformID)) {
			console.println(CommandUtils.inactiveConnectionMessage(platformID), ShellConsole.MSG_WARN);
		} else {
			console.println("Current connection " + platformID + " does not exist", ShellConsole.MSG_WARN);
		}
	}

	@Override
	public String getDescription() {
		return ("Show all environments for a given Database Group");
	}

	@Override
	public String getArguments() {
		return "<platformName> (optional) the name of the platform, current platform if empty.\n\t<environmentName> (optional) the name of the environment, all environments if empty";
	}

	@Override
	public String getExamples() {
		return "SHOW GROUP db01;\n\tSHOW GROUP;";
	}
}
