package com.upandcoding.broadsql.controller.shell.commands.core.set;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Switches how query results are displayed for the current session: {@code SET LIST ON|OFF} (also
 * accepts {@code TRUE}/{@code FALSE}, case-insensitive).
 *
 * <p>{@code OFF} (the default) shows results as a table, one row per line; {@code ON} shows each row
 * as a form, one column per line - useful for wide result sets that don't fit a screen width.
 *
 * <p>The argument is mandatory - the command fails with an error if it is missing or not one of
 * {@code ON}, {@code OFF}, {@code TRUE}, {@code FALSE}.
 */
public class CommandSetListMode extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandSetListMode.class);

	public CommandSetListMode() {
		super("SET LIST", "SELI");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		
		String[] arrListModes = { "ON", "OFF", "TRUE", "FALSE" };
		List<String> listModes = Arrays.asList(arrListModes);

		String[] args = parseArgs(query);

		String listMode = null;
		if (CommandUtils.isValidArgs(args)) {
			listMode = args[0].trim();
		}

		if (StringUtils.isNotBlank(listMode) && listModes.contains(listMode.toUpperCase())) {
			if ("OFF".equalsIgnoreCase(listMode) || "false".equalsIgnoreCase(listMode)) {
				this.setListMode(false);
				console.println("List mode OFF");
			} else if ("ON".equalsIgnoreCase(listMode) || "true".equalsIgnoreCase(listMode)) {
				this.setListMode(true);
				console.println("List mode ON");
			} else {
				if (this.listMode) {
					console.println("List mode ON");
				} else {
					console.println("List mode OFF");
				}
			}
			console.println("");
		} else {
			console.error("You must specify a valid list mode (ON/OFF, true/false)");
		}
	}

	@Override
	public boolean isHidden() {
		return (false);
	}

	@Override
	public String getDescription() {
		return ("Sets the display mode of query resuls: OFF=tab view (default), ON=form view");
	}

	@Override
	public String getArguments() {
		// TODO Auto-generated method stub
		return "ON/OFF or TRUE/FALSE";
	}

	@Override
	public String getExamples() {
		// TODO Auto-generated method stub
		return "SET LIST ON;\n\tSET LIST OFF;";
	}
}
