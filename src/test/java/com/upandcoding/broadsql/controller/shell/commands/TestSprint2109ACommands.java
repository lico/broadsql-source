package com.upandcoding.broadsql.controller.shell.commands;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowAutoCommit;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowExtensionErrors;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/** SPRINT 2109A: #12 (canonical keyword), #59 (SHOW EXTENSION ERRORS), #60 (metadata defaults), #61 (longest keyword wins). */
class TestSprint2109ACommands {

	private static class Bare extends Command {
		Bare(String... keywords) {
			super(keywords);
		}

		@Override
		public void execute(String query) {
		}
	}

	@Test
	void metadataMethodsDefaultToEmptyStrings() {
		Bare c = new Bare("BARE");
		Assertions.assertEquals("", c.getDescription());
		Assertions.assertEquals("", c.getArguments());
		Assertions.assertEquals("", c.getExamples());
	}

	@Test
	void longestKeywordWinsWhateverTheRegistrationOrder() throws BroadSQLException {
		Command shortCmd = new Bare("SHOW");
		Command longCmd = new Bare("SHOW TABLES");
		Command longer = new Bare("SHOW TABLES EXTRA");
		for (int i = 0; i < 20; i++) {
			CommandList list = new CommandList();
			// vary insertion order and add filler keys so HashMap iteration order changes
			for (int f = 0; f < i; f++) {
				list.put("FILLER" + f, new Bare("FILLER" + f));
			}
			if (i % 2 == 0) {
				list.put("SHOW", shortCmd);
				list.put("SHOW TABLES", longCmd);
				list.put("SHOW TABLES EXTRA", longer);
			} else {
				list.put("SHOW TABLES EXTRA", longer);
				list.put("SHOW TABLES", longCmd);
				list.put("SHOW", shortCmd);
			}
			Assertions.assertSame(longCmd, list.getCommandClassFromName("SHOW TABLES x"));
			Assertions.assertSame(longCmd, list.getCommandClassFromName("SHOW TABLES"));
			Assertions.assertSame(longer, list.getCommandClassFromName("SHOW TABLES EXTRA y"));
			Assertions.assertSame(shortCmd, list.getCommandClassFromName("SHOW other"));
		}
	}

	@Test
	void showAutoCommitIsCanonicalAndAutocommitRemainsAnAlias() {
		CommandShowAutoCommit cmd = new CommandShowAutoCommit();
		Assertions.assertEquals("SHOW AUTOCOMMIT", cmd.getKeywords()[0]);
		Assertions.assertTrue(List.of(cmd.getKeywords()).contains("AUTOCOMMIT"));
	}

	@Test
	void showExtensionErrorsReportsNoneThenRecordedFailures() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandInterpreter interpreter = CommandTestSupport.createCommandInterpreter(TestDatabaseConnections.defaultConsoleSettings(), console);
		CommandShowExtensionErrors cmd = CommandTestSupport.create(CommandShowExtensionErrors.class, console);
		cmd.setConsoleCommandInterpreter(interpreter);

		cmd.execute("SHOW EXTENSION ERRORS");
		Assertions.assertTrue(console.getOutput().contains("No extension errors"), console.getOutput());

		interpreter.getCommands().getConsoleCommandLoader().recordError("Unable to load commands from 'bad.jar': boom");
		console.clear();
		cmd.execute("SHOW EXTENSION ERRORS");
		Assertions.assertTrue(console.getOutput().contains("bad.jar"), console.getOutput());
		Assertions.assertTrue(console.getOutput().contains("boom"), console.getOutput());
	}

	private CommandLoader newLoader(CapturingShellConsole console) {
		CommandLoader loader = new CommandLoader();
		loader.shellConsoleSettings = TestDatabaseConnections.defaultConsoleSettings();
		loader.console = console;
		return loader;
	}

	@Test
	void conflictingKeywordIsDetectedRecordedAndNothingOfTheRejectedClassIsRegistered() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLoader loader = newLoader(console);

		Assertions.assertTrue(loader.checkNoErrorsInKeyword(ShowThing.class));
		// same keyword after case and whitespace normalization
		Assertions.assertFalse(loader.checkNoErrorsInKeyword(ClashingThing.class));

		Assertions.assertEquals(1, loader.getExtensionErrors().size());
		String error = loader.getExtensionErrors().get(0);
		Assertions.assertTrue(error.contains(ClashingThing.class.getCanonicalName()) && error.contains("show thing"), error);
		Assertions.assertTrue(console.getOutput().contains("conflicting keyword"), console.getOutput());

		// "CLASH ONE" must not have been left registered by the rejected class
		Assertions.assertTrue(loader.checkNoErrorsInKeyword(ShowThings.class));
	}

	@Test
	void prefixRelatedKeywordsAreAcceptedAndDispatchToTheLongestMatch() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLoader loader = newLoader(console);

		Assertions.assertTrue(loader.checkNoErrorsInKeyword(ShowThing.class));
		Assertions.assertTrue(loader.checkNoErrorsInKeyword(ShowThings.class));
		Assertions.assertTrue(loader.getExtensionErrors().isEmpty());

		Command thing = new ShowThing();
		Command things = new ShowThings();
		for (boolean thingFirst : new boolean[] { true, false }) {
			CommandList list = new CommandList();
			Command[] order = thingFirst ? new Command[] { thing, things } : new Command[] { things, thing };
			for (Command c : order) {
				for (String k : c.getKeywords()) {
					list.put(k, c);
				}
			}
			Assertions.assertSame(thing, list.getCommandClassFromName("SHOW THING x"));
			Assertions.assertSame(things, list.getCommandClassFromName("SHOW THINGS x"));
		}
	}

	@Test
	void everyRealCommandStillRegistersWithoutConflict() throws BroadSQLException {
		CapturingShellConsole console = new CapturingShellConsole();
		CommandLoader loader = newLoader(console);
		for (Class<? extends Command> commandClass : CommandCategoryCatalog.registeredClasses().keySet()) {
			Assertions.assertTrue(loader.checkNoErrorsInKeyword(commandClass), commandClass.getName() + ": " + console.getOutput());
		}
		Assertions.assertTrue(loader.getExtensionErrors().isEmpty(), loader.getExtensionErrors().toString());
	}
}

class ShowThing extends Command {
	public ShowThing() {
		super("SHOW THING", "SH TH");
	}

	@Override
	public void execute(String query) {
	}
}

class ShowThings extends Command {
	public ShowThings() {
		super("SHOW THINGS", "SH THS");
	}

	@Override
	public void execute(String query) {
	}
}

class ClashingThing extends Command {
	public ClashingThing() {
		super("CLASH ONE", "show   THING");
	}

	@Override
	public void execute(String query) {
	}
}
