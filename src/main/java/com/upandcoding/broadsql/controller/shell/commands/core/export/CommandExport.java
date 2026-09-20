package com.upandcoding.broadsql.controller.shell.commands.core.export;

import java.io.File;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Turns export mode on or off for the current session: {@code EXPORT [<fileName>]}.
 *
 * <p>With no argument, turns export mode off (subsequent output goes back to the screen). With a
 * file name, turns export mode on: every SQL statement run afterwards writes its result to that
 * file instead of the screen, until {@code EXPORT} is called again with no argument (or a new file
 * name). Unlike {@code DUMP}, which extracts one table in a single step, {@code EXPORT} only changes
 * where subsequent output goes.
 *
 * <p>If {@code fileName} has no extension, one is appended based on {@code DefaultFileFormat} in
 * {@code BroadSQL.ini} ({@code .xlsx}, {@code .ods}, {@code .csv} or {@code .txt} - defaults to
 * {@code .ods} if that parameter is absent or invalid). If it has no directory part, it is written
 * to the configured export folder ({@code DefaultFolder} in {@code BroadSQL.ini}); if it does
 * include a directory, that directory must already exist, or the command fails. The file extension
 * determines the output format: plain text, comma-separated (CSV, RFC 4180 quoting), Excel
 * ({@code .xlsx}), OpenDocument Spreadsheet ({@code .ods}), or MS Access ({@code .mdb}). The legacy
 * binary Excel format ({@code .xls}) is not supported - use {@code .xlsx} instead.
 *
 * <p><b>Recommended alternative for a one-shot query/table export</b>: {@code PULL <source> TO <name>
 * AS CSV/TXT} (see {@link CommandPull}, docs/PULL_TO_TEXT.md) is the modernized, unified replacement
 * for this command's CSV/TXT output - same command family as {@code PULL ... AS H2/XLSX/ODS}. Not a
 * drop-in replacement for every existing script, though: unlike this command's CSV output (always
 * comma) and TXT output (whatever {@code SET SEP} currently holds), {@code PULL ... AS CSV} uses the
 * separate {@code CsvSeparator} INI setting (semicolon if absent) and {@code AS TXT} always uses a
 * tab - neither honors {@code SET SEP} - and {@code PULL} rejects BLOB/CLOB/binary columns outright
 * rather than writing whatever {@code ResultSet.getString()} returns for them. This command is
 * unaffected by that addition and keeps working exactly as documented above.
 */
public class CommandExport extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandExport.class);

	public CommandExport() {
		super("EXPORT", "EXP", "EXTRACT", "EXT");
	}

	/**
	 * Parses the {@code fileName} argument out of an {@code EXPORT}/{@code EXP}/{@code EXTRACT}/
	 * {@code EXT} command line and resolves it to the actual path that will be written to.
	 *
	 * <p>Returns {@code null} when the command was given no argument (or a blank one), meaning
	 * export mode should be turned off. Otherwise: appends an extension based on
	 * {@code DefaultFileFormat} ({@link com.upandcoding.broadsql.controller.shell.ConsoleSettings#getDefaultFileExtension()})
	 * when {@code fileName} has none; if the resulting name has no directory part, prefixes it with
	 * the configured export folder ({@code DefaultFolder}); if it does have a directory part, that
	 * directory must already exist, or this method throws.
	 *
	 * @param query the full command line as typed, including the {@code EXPORT}/{@code EXP}/
	 *              {@code EXTRACT}/{@code EXT} keyword
	 * @return the resolved target file path, or {@code null} to turn export mode off
	 * @throws BroadSQLException if a directory was given explicitly and it does not exist
	 */
	public String getTargetFileName(String query) throws BroadSQLException {
		String result = null;

		String[] args = parseArgs(query);

		String fName = null;
		if (CommandUtils.isValidArgs(args)) {
			fName = args[0].trim();

			if (StringUtils.isBlank(fName)) {
				fName = null;
			} else {
				// Default Extension
				if ("".equals(StringUtils.substringAfterLast(fName, "."))) {
					fName = fName + "." + consoleSettings.getDefaultFileExtension();
				}

				if (fName.contains(SpringPropertiesConfig.getFileSep())) {
					String dirPath = StringUtils.substringBeforeLast(fName, SpringPropertiesConfig.getFileSep());
					File filePath = new File(dirPath);
					if (!filePath.exists()) {
						throw new BroadSQLException("Folder " + dirPath + " does not exist");
					}
				} else {
					fName = consoleSettings.getExtractFolderName() + fName;
				}
			}
			result = fName;
		}
		return result;
	}

	/**
	 * Turns export mode on (with the resolved target file, see {@link #getTargetFileName(String)})
	 * or off, and prints the resulting state to the console.
	 *
	 * @param query the full command line as typed
	 */
	@Override
	public void execute(String query) throws BroadSQLException {
			String fName = getTargetFileName(query);
			if (fName != null) {
				this.extractMode = true;
				this.extractFileName = fName;
				console.println("Export to file: " + this.extractFileName);
			} else {
				this.extractMode = false;
				console.println("Export mode: OFF");
			}
			console.println("");
	}

	@Override
	public String getDescription() {
		return ("Turns export mode ON or OFF and, if ON, specifies the target file name.\n\r\t\t\t\t\tSupported formats: text files, CSV, MS Excel (XLSX), OpenDocument Spreadsheet (ODS), MS Access(MDB)");
	}

	@Override
	public String getArguments() {
		return "<fileName> (optional) if empty, turns export off. Without directory information, files are exported to folder specified in parameter DefaultFolder, \n\tsection [General] of INI file BroadSQL.ini. If fileName has no extension, one is appended based on parameter DefaultFileFormat (XLSX, ODS, CSV or TXT; defaults to ODS).";
	}

	@Override
	public String getExamples() {
		return "EXPORT output;\n\tEXPORT c:\\temp\\output.xlsx;\n\tEXP;";
	}
}
