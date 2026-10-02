package com.upandcoding.broadsql.controller.shell.commands.core.misc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.NumberFormat;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/*
 * 
 * Runtime command
 *   - Displays memory
 *   	* RUNTIME;
 *   - Runs the garbage collector (gc)
 *   	* RUNTIME GC;
 *   - Execute a system command. Type the command between "".
 *   	* RUNTIME CMD DIR;
 *		* RUNTIME CMD "DEL C:\TEMP\EXCHANGE_RATE.xlsx";
 *		* RUNTIME CMD "DEL C:\TEMP\ERATES.TXT";
 *
 */
/**
 * Displays JVM memory usage, runs the garbage collector, or executes an OS command:
 * {@code RUNTIME}, {@code RUNTIME GC}, or {@code RUNTIME CMD <command>}.
 *
 * <p>With no argument, prints the JVM's max and free memory. With {@code GC}, runs the garbage
 * collector first, then prints the same figures. With {@code CMD}, runs {@code <command>} as an OS
 * command (via {@code cmd /c}) and prints its output - only the first whitespace-separated token is
 * used as the command unless it's quoted as a single argument, so a command with its own arguments
 * must be quoted, e.g. {@code RUNTIME CMD "DEL C:\TEMP\FILE.TXT"}. Hidden from {@code HELP}.
 */
public class CommandRuntime extends Command {

	public CommandRuntime() {
		super("RUNTIME");
	}

	@Override
	public boolean isHidden() {
		return (true);
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String expression = null;
		if (CommandUtils.isValidArgs(args)) {
			expression = args[0].trim();
		}

		Runtime runTime = Runtime.getRuntime();
		if (StringUtils.isBlank(expression)) {
			console.writeln("Max memory: " + NumberFormat.getInstance().format(runTime.maxMemory()));
			console.writeln("Free memory: " + NumberFormat.getInstance().format(runTime.freeMemory()));
			
		} else if ("GC".equalsIgnoreCase(expression)) {
			console.writeln("Executing the garbage collector");
			runTime.gc();
			console.writeln("Max memory: " + NumberFormat.getInstance().format(runTime.maxMemory()));
			console.writeln("Free memory: " + NumberFormat.getInstance().format(runTime.freeMemory()));
			
		} else if ("CMD".equalsIgnoreCase(expression)) {
			if (args.length > 1) {
				String dosCmd = args[1];
				if (StringUtils.isNotBlank(dosCmd)) {
					String dosCmdReal = "cmd /c " + dosCmd;
					try {
						Process process = runTime.exec(dosCmdReal);
						InputStream is = process.getInputStream();
						InputStreamReader isr = new InputStreamReader(is);
						BufferedReader br = new BufferedReader(isr);
						String line;

						console.writeln("Output of command '" + dosCmd + "':");
						while ((line = br.readLine()) != null) {
							console.writeln(line);
						}
					} catch (IOException ie) {
						console.writeln(ie.getMessage());
					}
				} else {
					console.error("You must enter a valid command");
				}
			} else {
				console.error("You must enter a valid command");
			}
		}
		console.println("");
	}

	@Override
	public String getDescription() {
		return ("Displays the amount of memory. Used with argument GC, cleans the garbage collector. Use with argument CMD, executes a DOS command");
	}

	@Override
	public String getArguments() {
		return "GC (optional)";
	}

	@Override
	public String getExamples() {
		return "RUNTIME;\n\tExample: RUNTIME GC;\n\tExample: RUNTIME CMD dir /s;";
	}
}
