package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.set.CommandSetSeparator;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;

/**
 * {@link CommandSetSeparator} needs no database. Test values are chosen to avoid the named-value
 * collisions in {@code CommandSetSeparator.names} ({@code DEFAULT}/{@code TAB} both map to tab,
 * {@code ENTER}/{@code NEWLINE} both map to newline) - {@code COMMA}, {@code PIPE} and
 * {@code SEMICOLON} are each the only name for their character.
 */
class TestCommandSetSeparator {

	@Test
	void setsASingleCharacterSeparatorDirectly() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandSetSeparator cmd = CommandTestSupport.create(CommandSetSeparator.class, console);

		cmd.execute("SET SEP ,");

		Assertions.assertEquals(',', cmd.getSeparator());
		Assertions.assertTrue(console.getOutput().contains(", (COMMA)"),
				"expected the comma separator with its name, got:\n" + console.getOutput());
	}

	@Test
	void setsASeparatorByName() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandSetSeparator cmd = CommandTestSupport.create(CommandSetSeparator.class, console);

		cmd.execute("SET SEP PIPE");

		Assertions.assertEquals('|', cmd.getSeparator());
		Assertions.assertTrue(console.getOutput().contains("| (PIPE)"),
				"expected the pipe separator with its name, got:\n" + console.getOutput());
	}

	@Test
	void fallsBackToPipeForAnUnrecognizedName() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandSetSeparator cmd = CommandTestSupport.create(CommandSetSeparator.class, console);

		cmd.execute("SET SEP NOTANAME");

		Assertions.assertEquals('|', cmd.getSeparator());
	}

	@Test
	void displaysTheCurrentSeparatorWithoutChangingItWhenNoArgumentIsGiven() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandSetSeparator cmd = CommandTestSupport.create(CommandSetSeparator.class, console);
		cmd.setSeparator(';');

		cmd.execute("SET SEPARATOR");

		Assertions.assertEquals(';', cmd.getSeparator());
		Assertions.assertTrue(console.getOutput().contains("; (SEMICOLON)"),
				"expected the semicolon separator with its name, got:\n" + console.getOutput());
	}
}
