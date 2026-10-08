package com.upandcoding.broadsql.controller.shell.commands.extensions;

import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.dao.load.LoadCommandParser;
import com.upandcoding.broadsql.dao.load.LoadStatement;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;

/**
 * {@code BATCHLOAD <table> <file> [PREVIEW | EXECUTE]} - deprecated: this command no longer has its
 * own execution path. It is a thin adapter that parses exactly like {@code LOAD} (see
 * {@link LoadCommandParser}) and routes to the same safe INSERT engine, printing a deprecation
 * notice first. Use {@code LOAD} instead; see {@code HELP LOAD} and docs/SQL_LOAD.md.
 *
 * <p>SPRINT 0912B (docs/TECHNICAL_CHANGE.md, 12/09/2026) retired {@code BATCHLOAD}'s previous,
 * independent implementation (unchecked-identifier SQL construction, silent per-batch commits,
 * swallowed {@code SQLException}s) along with {@code LOAD}'s own - "batch" is now purely an internal
 * execution detail of the one {@code LOAD} engine, never a separate user-facing command concept.
 */
public class CommandDirectLoadBatch extends Command {

	public CommandDirectLoadBatch() {
		super("BATCHLOAD", "BALO");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		LoadStatement statement = LoadCommandParser.parse(args, "BATCHLOAD");

		CommandDirectLoadDirect delegate = new CommandDirectLoadDirect();
		delegate.setSqlDatabase(sqlDatabase);
		delegate.setConsole(console);
		delegate.setConsoleSettings(consoleSettings);
		delegate.setConsoleCommandInterpreter(getConsoleCommandInterpreter());
		delegate.setDatabaseConnectionsVault(getDatabaseConnectionsVault());
		delegate.setSession(getSession());
		delegate.runLoad(statement, true);
	}

	@Override
	public String getDescription() {
		return "Deprecated - use LOAD instead. Routes to the same safe INSERT engine as LOAD.";
	}

	@Override
	public String getArguments() {
		return "BATCHLOAD <table> <file> [PREVIEW | EXECUTE] - deprecated, identical to LOAD (see HELP LOAD)";
	}

	@Override
	public String getExamples() {
		return "BATCHLOAD CUSTOMER customer.csv EXECUTE";
	}

	/** SPRINT 2409K: same arguments as LOAD, which this deprecated spelling delegates to. */
	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.TABLE, CompletionEntityType.EXPORT_FOLDER_FILE);
	}
}
