package com.upandcoding.broadsql.controller.shell.commands.core.misc;

import java.io.IOException;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;

/**
 * Clears the console screen: {@code CLS} or {@code CLEAR}.
 *
 * <p>Invokes the operating system's native clear-screen command ({@code cmd /c cls}), which only
 * works on Windows. If that fails (e.g. on Linux) and a database connection is active, falls back to
 * printing enough blank lines to scroll the screen; with no active connection, the failure is simply
 * reported and the screen is left as is.
 */
public class CommandCls extends Command {

	public CommandCls() {
		super("CLS", "CLEAR");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		try {
			new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
		} catch (IOException | InterruptedException e) {
			console.error(e.getLocalizedMessage());
			if (sqlDatabase != null) {
				for (int i = 0; i < 75; i++) {
					console.println("");
				}
			}
		}
		/*
		if (sqlDatabase != null) {
			for (int i = 0; i < 75; i++) {
				shellConsole.println("");
			}
		}*/
	}

	@Override
	public String getDescription() {
		return ("Clear screen");
	}

	@Override
    public String getArguments() {
	    return "";
    }

	@Override
    public String getExamples() {
	    return "CLS; or CLEAR;";
    }
}
