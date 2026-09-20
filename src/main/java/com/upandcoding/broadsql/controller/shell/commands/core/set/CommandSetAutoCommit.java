/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.upandcoding.broadsql.controller.shell.commands.core.set;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;

/**
 * Toggles JDBC auto-commit for the current connection: {@code SET AUTOCOMMIT ON|OFF} (also accepts
 * {@code TRUE}/{@code FALSE} as synonyms, case-insensitive).
 *
 * <p>The argument is mandatory - the command fails with an error if it is missing or not one of
 * {@code ON}, {@code OFF}, {@code TRUE}, {@code FALSE}. The change is a live JDBC connection setting:
 * it applies only to the current session and is not persisted across reconnects.
 *
 * <p>The console always reports the resulting state, and additionally warns that updates can no
 * longer be rolled back whenever auto-commit ends up ON.
 */
public class CommandSetAutoCommit extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandSetAutoCommit.class);

	public CommandSetAutoCommit() {
		super("SET AUTOCOMMIT", "SE AU");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] arrAcModes = { "ON", "OFF", "TRUE", "FALSE" };
		List<String> acModes = Arrays.asList(arrAcModes);

		String[] args = parseArgs(query);

		String acMode = null;
		if (CommandUtils.isValidArgs(args)) {
			acMode = args[0].trim();
		}

		if (StringUtils.isNotBlank(acMode) && acModes.contains(acMode.toUpperCase())) {
			if ("OFF".equalsIgnoreCase(acMode) || "false".equalsIgnoreCase(acMode)) {
				sqlDatabase.setAutoCommit(false);
			} else if ("ON".equalsIgnoreCase(acMode) || "true".equalsIgnoreCase(acMode)) {
				sqlDatabase.setAutoCommit(true);
			}
			if (sqlDatabase.isAutoCommit()) {
				console.println("Autocommit ON : all updates done without possibility of rollback", ShellConsole.MSG_WARN);
			} else {
				console.println("Autocommit OFF", ShellConsole.MSG_INFO);
			}
			console.println("");
		} else {
			console.error("You must specify a valid autocommit mode (ON/OFF, true/false)");
		}
	}

	@Override
	public boolean isHidden() {
		return (false);
	}

	@Override
	public String getArguments() {
		// TODO Auto-generated method stub
		return "ON/OFF or TRUE/FALSE";
	}

	@Override
	public String getExamples() {
		// TODO Auto-generated method stub
		return "SET AUTOCOMMIT ON;";
	}

	@Override
	public String getDescription() {
		// TODO Auto-generated method stub
		return "Turns autocommit ON or OFF";
	}
}
