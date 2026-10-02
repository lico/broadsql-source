package com.upandcoding.broadsql.controller.shell.commands.extensions;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.dao.load.LoadCommandParser;
import com.upandcoding.broadsql.dao.load.LoadExecutor;
import com.upandcoding.broadsql.dao.load.LoadPlan;
import com.upandcoding.broadsql.dao.load.LoadPreviewRenderer;
import com.upandcoding.broadsql.dao.load.LoadResult;
import com.upandcoding.broadsql.dao.load.LoadSourceReader;
import com.upandcoding.broadsql.dao.load.LoadStatement;
import com.upandcoding.broadsql.dao.load.LoadTarget;
import com.upandcoding.broadsql.dao.load.LoadTargetResolver;
import com.upandcoding.broadsql.dao.load.LoadValidator;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;

/**
 * {@code LOAD <table> <file> [PREVIEW | EXECUTE]} loads a CSV file into a table with INSERT,
 * safely: every value is bound through a JDBC {@code PreparedStatement} parameter (never
 * concatenated into SQL text), and the target table/columns are resolved and validated against
 * database metadata before any SQL is built. See the [Import guide](../import.md) for the workflow,
 * CSV format, conversion rules and transaction caveats.
 *
 * <p>The legacy {@code LOAD CREATE <table> <file>} form still works unchanged (only {@code INSERT}
 * exists now, so the mode keyword is optional and, when present, only {@code CREATE} is accepted;
 * {@code UPDATE} is refused with an explicit migration message, see {@link LoadCommandParser}).
 *
 * <p>{@code PREVIEW} validates the source against the target (column mapping, per-value type
 * conversion) and reports the outcome without writing anything or asking for confirmation. A plain
 * {@code LOAD} runs the same preflight, then, if it passed, asks for confirmation before writing
 * (default answer is No), unless it is running inside a {@code @}/{@code LIB RUN} script, in
 * which case it refuses outright rather than blocking on a prompt nothing can answer; add
 * {@code EXECUTE} to authorize the write immediately, interactively or from a script. Any preflight
 * failure (an unrecognized source column, or any row that fails to convert) refuses the entire
 * load; there is no partial/best-effort load of just the rows that happened to be valid.
 *
 * <p>The load is atomic: values are batched internally with JDBC {@code addBatch()}/
 * {@code executeBatch()} for efficiency, but nothing is committed until every batch has succeeded,
 * and any failure rolls back everything already written in this command.
 */
public class CommandDirectLoadDirect extends Command {

	public CommandDirectLoadDirect() {
		super("LOAD", "LO");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		LoadStatement statement = LoadCommandParser.parse(args, "LOAD");
		runLoad(statement, false);
	}

	/** Shared by {@code BATCHLOAD}'s deprecated adapter ({@link CommandDirectLoadBatch}). */
	void runLoad(LoadStatement statement, boolean deprecatedInvocation) throws BroadSQLException {
		if (deprecatedInvocation) {
			console.println("BATCHLOAD is deprecated. Use LOAD instead.");
		}

		String resolvedFileName = resolveFileName(statement.getFileName());
		LoadTarget target = LoadTargetResolver.resolve(sqlDatabase, statement.getTableName());
		LoadSourceReader source = LoadSourceReader.read(resolvedFileName, consoleSettings.getCsvSeparator());
		LoadPlan plan = LoadValidator.validate(target, resolvedFileName, source);

		String connectionId = sqlDatabase.getPlatform() != null ? sqlDatabase.getPlatform().getId() : null;
		String commandLabel = deprecatedInvocation ? "BATCHLOAD" : "LOAD";

		if (statement.getExecutionMode() == LoadStatement.ExecutionMode.PREVIEW) {
			console.println(LoadPreviewRenderer.render(plan, connectionId));
			return;
		}

		if (!plan.isReadyToExecute()) {
			console.println(LoadPreviewRenderer.render(plan, connectionId));
			return;
		}

		if (statement.getExecutionMode() == LoadStatement.ExecutionMode.CONFIRM) {
			if (getConsoleCommandInterpreter() != null && getConsoleCommandInterpreter().isRunningInsideScript()) {
				console.println(LoadPreviewRenderer.render(plan, connectionId));
				console.error(commandLabel + " requires EXECUTE to authorize a write when running inside a script (e.g. "
						+ commandLabel + " " + statement.getTableName() + " " + statement.getFileName()
						+ " EXECUTE;). No changes have been made.");
				return;
			}

			console.println(LoadPreviewRenderer.render(plan, connectionId));
			String confirm = console.inputField(sqlDatabase.getPlatform(),
					"Load " + plan.getValidRowCount() + " rows into " + target.getQualifiedName() + "? [y/N]", "",
					true, false, true, null);
			boolean confirmed = confirm != null && (confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes"));
			if (!confirmed) {
				console.println("Load aborted. No changes have been made.");
				return;
			}
		}

		LoadResult result = LoadExecutor.execute(sqlDatabase, plan);
		console.println(result.getRowsInserted() + " row(s) inserted into " + target.getQualifiedName()
				+ " (source rows read: " + result.getRowsAttempted() + ", committed).");
		console.println("");
	}

	private String resolveFileName(String fileName) {
		if (StringUtils.contains(fileName, SpringPropertiesConfig.getFileSep())) {
			return fileName;
		}
		String folder = consoleSettings.getExtractFolderName();
		if (StringUtils.isBlank(folder)) {
			return fileName;
		}
		if (!folder.endsWith(SpringPropertiesConfig.getFileSep())) {
			folder = folder + SpringPropertiesConfig.getFileSep();
		}
		return folder + fileName;
	}

	@Override
	public String getDescription() {
		return "Loads records from a source file into a table using safe, parameter-bound INSERT statements";
	}

	@Override
	public String getDetailedDescription() {
		return getDescription() + ". Every value is bound through a JDBC PreparedStatement parameter, never "
				+ "concatenated into SQL text, and the target table/columns are resolved against real database "
				+ "metadata before any SQL is built. PREVIEW validates without writing; a plain LOAD asks for "
				+ "confirmation before writing (default No) unless running inside a script, where it requires "
				+ "EXECUTE instead. Any unrecognized source column, or any row that fails to convert, refuses the "
				+ "whole load; there is no partial load of only the valid rows.";
	}

	@Override
	public String getArguments() {
		return "LOAD <table> <file> [PREVIEW | EXECUTE]\n\twhere:\n\t<table> is a valid table name, optionally SCHEMA.TABLE\n\t"
				+ "<file> is the source CSV file (semicolon-separated by default; see CsvSeparator in BroadSQL.ini)\n\t"
				+ "PREVIEW validates and reports without writing or asking for confirmation\n\t"
				+ "EXECUTE authorizes the write immediately, without asking for confirmation (required inside a script)\n\t"
				+ "The legacy LOAD CREATE <table> <file> form is still accepted (CREATE is now the only mode, so it is optional)";
	}

	@Override
	public String getExamples() {
		return "LOAD CUSTOMER customer.csv\n\tLOAD CUSTOMER customer.csv PREVIEW\n\tLOAD CUSTOMER customer.csv EXECUTE\n\t"
				+ "LOAD CRM.CUSTOMER c:\\temp\\customer.csv EXECUTE";
	}

	/** SPRINT 2409K: a table, then the source file (a bare name is read from the export folder, DefaultFolder, see resolveFileName). */
	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.TABLE, CompletionEntityType.EXPORT_FOLDER_FILE);
	}
}
