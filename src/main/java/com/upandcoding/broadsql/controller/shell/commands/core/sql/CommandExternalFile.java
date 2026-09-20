package com.upandcoding.broadsql.controller.shell.commands.core.sql;

import java.io.IOException;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptCommand;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptCommandsList;

/**
 * Runs the statements stored in a script file: {@code @<fileName>}.
 *
 * <p>{@code fileName} is mandatory. The file may contain any mix of BroadSQL commands and raw SQL,
 * separated by {@code ;} (a statement can span several lines). Lines starting with {@code --}, and
 * {@code /* ... * /} block comments (single- or multi-line), are stripped before the file is split
 * into statements. Each statement is executed exactly as if typed at the prompt; a statement that
 * itself starts with {@code @} runs another script, so scripts can call other scripts.
 *
 * <p>A statement that fails is reported and the rest of the script still runs. A missing file, or a
 * file that yields no statements at all, is reported as an error and stops the command.
 */
public class CommandExternalFile extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandExternalFile.class);

	public CommandExternalFile() {
		super("@");
	}

	@Override
	public boolean isHidden() {
		return (true);
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		if (CommandUtils.isValidArgs(args)) {
			runScriptFile(args[0].trim());
		} else {
			console.error("You must provide a file name");
		}
	}

	/**
	 * Runs every statement in {@code qryFileName} - the shared engine behind both {@code @<path>}
	 * (above) and {@code SCRIPT RUN <name>} ({@code CommandScriptRun}, which resolves a catalog name to
	 * an absolute path and calls this directly), so both share the exact same comment-stripping/
	 * statement-splitting/dispatch behavior. A missing file, or one with no statements, is reported
	 * (not thrown) - the rest of the caller isn't interrupted.
	 */
	public void runScriptFile(String qryFileName) throws BroadSQLException {
		ScriptCommandsList queries = new ScriptCommandsList(qryFileName);
		try {
			queries.load();
			if (queries != null && !queries.isEmpty()) {
				console.println("Found " + queries.size() + " queries");
				for (ScriptCommand oQuery : queries) {
					String qry = oQuery.getQuery();

					if (StringUtils.isNotBlank(qry)) {
						qry = qry.trim();
						if (qry.endsWith(";")) {
							qry = StringUtils.substringBeforeLast(qry, ";");
						}

						if (StringUtils.isNotBlank(qry)) {
							if (qry.startsWith("@")) {
								console.println(qry);
								execute(qry);
							}
							String qryDisplay = StringUtils.replace(qry, "%", "]]z0a23-=");
							qryDisplay = StringUtils.replace(qryDisplay, "]]z0a23-=", "%%");
							console.println(qryDisplay);
							try {
								if (CommandUtils.isSelectStatement(qry)) {
									CommandUtils.substituteMacros(qry);
								}
								getConsoleCommandInterpreter().setQuery(qry);
								getConsoleCommandInterpreter().executeCommand();
							} catch (BroadSQLException se) {
								console.error(se);
								console.println("");
							}
						}
					}
				}
			} else {
				console.error("The input file in '" + qryFileName + "' does not contain queries");
			}
		} catch (IOException ie) {
			console.error("File does not exist (" + qryFileName + ")");
		}
	}

	@Override
	public String getDescription() {
		return ("Executes the SQL queries located in an external text file");
	}

	@Override
	public String getArguments() {
		return "@ followed by file name (mandatory)";
	}

	@Override
	public String getExamples() {
		return "@c:\\temp\\queries.txt";
	}
}
