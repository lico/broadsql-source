package com.upandcoding.broadsql.controller.shell.commands;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidate;
import com.upandcoding.broadsql.controller.shell.completion.EntityCompletionService;
import com.upandcoding.broadsql.controller.shell.commands.core.help.CommandHelp;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandRepeat;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;

/** GitHub #196: {@code REPEAT} is discoverable (category, HELP) and its fixed-order grammar is offered by TAB completion. */
class TestRepeatCompletionAndHelp extends ScriptingTestBase {

	private List<String> complete(String... words) {
		EntityCompletionService service = EntityCompletionService.standard(interpreter.getCommands(), db);
		List<String> values = new ArrayList<>();
		for (CompletionCandidate c : service.complete(List.of(words), words.length - 1)) {
			values.add(c.getValue());
		}
		return values;
	}

	@Test
	void theTargetPositionOffersTheTargets() throws Exception {
		lib("monitor.sql", "SELECT 1;\n");
		Assertions.assertEquals(List.of("EVERY", "/", "BEGIN", "LIB"), complete("REPEAT", ""));
		Assertions.assertEquals(List.of("EVERY"), complete("REPEAT", "EV"));
		Assertions.assertEquals(List.of("RUN"), complete("REPEAT", "LIB", ""));
		Assertions.assertEquals(List.of("monitor.sql"), complete("REPEAT", "LIB", "RUN", ""));
		Assertions.assertEquals(List.of("@monitor.sql"), complete("REPEAT", "@mon"));
	}

	@Test
	void theClausesFollowTheFixedOrder() {
		Assertions.assertEquals(List.of("EVERY"), complete("REPEAT", "/", ""));
		Assertions.assertEquals(List.of("EVERY"), complete("REPEAT", "LIB", "RUN", "monitor.sql", ""));
		Assertions.assertEquals(List.of("EVERY"), complete("REPEAT", "@monitor.sql", ""));
		Assertions.assertEquals(List.of(), complete("REPEAT", "EVERY", ""), "a duration is free text");
		Assertions.assertEquals(List.of("FOR", "COUNT", "TO"), complete("REPEAT", "EVERY", "10s", ""));
		Assertions.assertEquals(List.of("TO"), complete("REPEAT", "EVERY", "10s", "COUNT", "5", ""));
		Assertions.assertEquals(List.of("AS"), complete("REPEAT", "EVERY", "10s", "TO", "m.csv", ""));
		Assertions.assertEquals(List.of("CSV", "JSON", "TEXT"), complete("REPEAT", "EVERY", "10s", "TO", "m", "AS", ""));
		Assertions.assertEquals(List.of(), complete("REPEAT", "EVERY", "10s", "TO", "m", "AS", "CSV", ""));
	}

	@Test
	void insideABlockSqlCompletionApplies() {
		Assertions.assertEquals(List.of(), complete("REPEAT", "BEGIN", "SELECT", ""));
		Assertions.assertEquals(List.of("EVERY"), complete("REPEAT", "BEGIN", "SELECT", "1;", "END", ""));
	}

	/** HELP with every registered command class available to its loader, as in {@code TestSprint2309THelpAndCompletion}. */
	@SuppressWarnings("unchecked")
	private String help(String query) throws Exception {
		CapturingShellConsole helpConsole = new CapturingShellConsole();
		CommandInterpreter helpInterpreter = CommandTestSupport.createCommandInterpreter(settings, helpConsole);
		Field field = CommandLoader.class.getDeclaredField("availableClasses");
		field.setAccessible(true);
		HashMap<String, String> classes = (HashMap<String, String>) field.get(helpInterpreter.getCommands().getConsoleCommandLoader());
		for (Class<? extends Command> commandClass : CommandCategoryCatalog.registeredClasses().keySet()) {
			classes.put(commandClass.getName(), commandClass.getName());
		}
		CommandHelp cmd = CommandTestSupport.create(CommandHelp.class, null, helpConsole, settings);
		cmd.setConsoleCommandInterpreter(helpInterpreter);
		cmd.execute(query);
		return helpConsole.getOutput();
	}

	@Test
	void repeatIsARunningQueriesCommandWithHelp() throws Exception {
		Assertions.assertEquals(CommandCategory.RUNNING_QUERIES, CommandCategoryCatalog.categoryOf(new CommandRepeat()));
		Assertions.assertTrue(CommandCategoryCatalog.isDocumented(new CommandRepeat()));
		String command = help("HELP REPEAT");
		Assertions.assertTrue(command.contains("EVERY <interval>"), command);
		Assertions.assertTrue(command.contains("s, m or h"), command);
		Assertions.assertTrue(help("HELP QUERY").contains("  REPEAT "), "listed with the Running Queries commands");
		Assertions.assertTrue(help("HELP ALL").contains("  REPEAT "));
	}
}
