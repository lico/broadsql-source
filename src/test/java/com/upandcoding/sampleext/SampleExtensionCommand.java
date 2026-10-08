package com.upandcoding.sampleext;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;

/**
 * A user extension command, outside BroadSQL's own packages, as a user would write one: {@code TestExtensionsFolder}
 * packs it into a JAR in an installation's {@code extensions} folder and checks BroadSQL registers it.
 */
public class SampleExtensionCommand extends Command {

	public SampleExtensionCommand() {
		super("SAMPLE EXTENSION");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		console.println("sample extension ran");
	}

	@Override
	public String getDescription() {
		return "A sample extension command";
	}
}
