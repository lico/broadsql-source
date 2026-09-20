package com.upandcoding.broadsql.controller.shell.commands.core.export;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Extracts the full content of a table to a local file: {@code DUMP <tableName>}.
 *
 * <p>{@code tableName} is mandatory and may be schema-qualified (e.g. {@code DUMP PUBLIC.CUSTOMER}).
 * The command fails with an error if the table does not exist, or if no table name is given.
 *
 * <p>The output file is written to the configured export folder ({@code DefaultFolder} in
 * {@code BroadSQL.ini}, {@code c:\temp\} by default), named after the table as typed (schema
 * included, if given). The file extension is chosen automatically from the table's row count:
 * tab-separated {@code .txt} beyond the configured threshold ({@code MaxRowXLSX} in
 * {@code BroadSQL.ini}), or the extension matching {@code DefaultFileFormat} (XLSX/ODS/CSV/TXT,
 * defaults to ODS) at or below it - including when the row count itself could not be determined, in
 * which case that same default format is used as a fallback.
 *
 * <p>On success, the console reports the number of records extracted and the destination file path.
 * There is currently no option to choose the destination, the file name, or the format explicitly.
 *
 * <p><b>Recommended alternative</b>: {@code PULL <tableName> TO <name> AS CSV/TXT/XLSX/ODS} (see
 * {@link CommandPull}, docs/PULL_TO_TEXT.md, docs/PULL_TO_SPREADSHEET.md) lets you choose the format
 * and destination name explicitly, instead of DUMP's automatic row-count-based choice. Not a drop-in
 * replacement for every existing script: {@code PULL ... AS CSV} uses the separate {@code CsvSeparator}
 * INI setting (semicolon if absent), not {@code DefaultFileFormat}/row-count-based selection, and
 * rejects BLOB/CLOB/binary columns outright rather than writing whatever {@code ResultSet.getString()}
 * returns for them. This command is unaffected by that addition and keeps working exactly as documented
 * above.
 */
public class CommandDumpTable extends Command {

	public CommandDumpTable() {
		super("DUMP");
	}

	/**
	 * Extracts the full content of the table named in {@code query} to a local file.
	 *
	 * <p>Fails with a console error if no table name is given, or if the table does not exist.
	 * Otherwise resolves the table's row count ({@code -1} if it could not be determined) and picks
	 * the file extension: a tab-separated {@code .txt} beyond {@code MaxRowXLSX}, or the extension
	 * matching {@code DefaultFileFormat}
	 * ({@link com.upandcoding.broadsql.controller.shell.ConsoleSettings#getDefaultFileExtension()})
	 * at or below it. The file is written to the configured export folder ({@code DefaultFolder}),
	 * named after {@code tableName} as typed.
	 *
	 * @param query the full command line as typed, including the {@code DUMP} keyword
	 */
	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		String tableName = null;
		String fileExtension = ".txt";
		if (CommandUtils.isValidArgs(args)) {
			tableName = args[0].trim();
		}

		if (StringUtils.isNotBlank(tableName)) {
			String schemaNamePattern = null;
			String tableNamePattern = tableName;
			if (tableName.contains(".")) {
				schemaNamePattern = CommandUtils.getSchemaName(tableName);
				tableNamePattern = CommandUtils.getTableName(tableName);
				if (StringUtils.isBlank(schemaNamePattern)) {
					schemaNamePattern = null;
				}
			}
			
			if (sqlDatabase.existsTable(schemaNamePattern, tableNamePattern)) {
				// Check number of records
				int nbRecords = 0;
				try {
					nbRecords = sqlDatabase.getNumberOfRecords(tableName);
				} catch (BroadSQLException se) {
					nbRecords = -1;
				}
				if (nbRecords <= consoleSettings.getMaxRowXlsx()) {
					fileExtension = "." + consoleSettings.getDefaultFileExtension();
				}
				// Toggle extraction ON
				this.extractMode = true;
				sqlDatabase.setToScreen(false);
				this.extractFileName = consoleSettings.getExtractFolderName() + tableName + fileExtension;
				sqlDatabase.setSep('\t');
				sqlDatabase.setFileName(this.extractFileName);
				// Extract
				try {
					String dmpQuery = "SELECT * FROM " + tableName;
					sqlDatabase.executeSelectQuery(dmpQuery);
					console.println("" + nbRecords + " records extracted to " + this.extractFileName);
				} catch (BroadSQLException se) {
					console.error(se);
					console.println("");
				}
				// Toggle extraction OFF
				this.extractMode = false;
				sqlDatabase.setToScreen(true);
				sqlDatabase.setSep('\t');
				console.println("");
			} else {
				console.error("Table name '" + tableName + " ' does not exist");
			}
		} else {
			console.error(BroadSQLErrorMessages.ERR_GAL_01);
		}
	}
	
	@Override
	public String getDescription() {
		return ("Extract the full content of a table in a local file");
	}

	@Override
	public String getArguments() {
		return "table name (mandatory). By default the files are located in the c:\\temp directory. Format is chosen by parameter DefaultFileFormat (XLSX, ODS, CSV or TXT; defaults to ODS), except beyond MaxRowXLSX rows where a tab-separated .txt is always used.";
	}

	@Override
	public String getExamples() {
		return "DUMP customer\n\tExtracts the content of table CUSTOMER in file customer.ods (or another extension, depending on DefaultFileFormat)";
	}
}
