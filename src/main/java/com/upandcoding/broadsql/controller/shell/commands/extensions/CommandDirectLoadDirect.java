/*
 * DIRECT LOAD command
 * Objective: To insert rows from a file in a table. Each row of the file contains 
 * the data for an insert in the target table. Fields are separated by semicolons (not 
 * customizable at the moment).
 * 
 * File format: 
 * The file must be CSV separated. The extension can be any.  
 * The first line must contain headers AND it is mandatory that the headers
 * contain the real names of the fields. The fields can be in any order in the 
 * file, also there's no need to specify all fields, only those that are 
 * mandatory for the insert must be present, which is very flexible.
 * 
 * The program checks the metadata of the table to retrieve the type of each field.  
 * Not all types are supported, but main types are : Numeric, Date, Varchar
 * 
 * See also class SQLDatabase, method:
 * public void directLoad(String tableName, String fileName) throws SQLException, ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {
 * 
 * TODO:
 * - Add error messages when file contains a data that is not of the expected type
 * - Let the user choose a field separator, different of default semi-colon
 * 
 * @author UpAndCoding.com, December 2011
 */
package com.upandcoding.broadsql.controller.shell.commands.extensions;

import java.io.File;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Loads a semicolon-separated CSV file into a table, one row at a time:
 * {@code LOAD <CREATE|UPDATE> <tableName> <fileName>}. All three arguments are mandatory, and
 * {@code tableName} must already exist (checked up front, with a clear error if it doesn't).
 *
 * <p>The file's first line must be a header naming real columns of {@code tableName}; columns can be
 * listed in any order and only the ones you need have to be present. In {@code CREATE} mode, every
 * header column is inserted. In {@code UPDATE} mode, the <em>first</em> header column is the key used
 * to locate the row (it is not itself updated) and the remaining columns are set. Each value is
 * converted to the target column's real type (numeric, boolean, text, or {@code dd-MMM-yy} dates,
 * plus the literal {@code sysdate}); a value that doesn't convert is stored as {@code NULL} rather
 * than aborting the row. If {@code fileName} has no path, it is resolved inside the configured export
 * folder ({@code DefaultFolder} in {@code BroadSQL.ini}).
 *
 * <p>A row whose insert/update fails is skipped (logged) and the rest of the file still runs. A
 * {@code <fileName-without-extension>_LOG.txt} file is always written next to the source file with
 * one entry per row, and the console reports how many rows were attempted versus actually
 * inserted/updated. For large files, {@code BATCHLOAD} runs the whole file as one transaction and is
 * faster.
 */
public class CommandDirectLoadDirect extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandDirectLoadDirect.class);

	static final String UPDATE = "UPDATE";
	static final String CREATE = "CREATE";

	private String tableName;
	private String fileName;
	private String updateMode;

	public CommandDirectLoadDirect() {
		super("LOAD", "LO");
	}

	private void getParametersFromQuery(String query) {
		fileName = null;
		tableName = null;
		updateMode = null;

		String[] args = parseArgs(query);
		if (args != null && args.length == 3) {
			updateMode = args[0].trim();
			tableName = args[1].trim();
			fileName = args[2].trim();

			if (StringUtils.isNotBlank(fileName)) {
				fileName = fileName.trim();
				if (!StringUtils.contains(fileName, SpringPropertiesConfig.getFileSep())) {
					String folder = consoleSettings.getExtractFolderName();
					if (StringUtils.isNotBlank(folder)) {
						if (!folder.endsWith(SpringPropertiesConfig.getFileSep())) {
							folder = folder + SpringPropertiesConfig.getFileSep();
						}
					} else {
						log.debug("No default folder");
					}
					fileName = folder + fileName;
				}
			} else {
				fileName = null;
			}

		}
	}

	public void execute(String query) throws BroadSQLException {
		if (StringUtils.isNotBlank(query)) {
			getParametersFromQuery(query);
			/*
			log.debug("fileName={}", fileName);
			log.debug("tableName={}", tableName);
			log.debug("updateMode={}", updateMode);
			*/
			if (StringUtils.isNotBlank(tableName) && StringUtils.isNotBlank(fileName) && StringUtils.isNotBlank(updateMode)) {
				if (this.sqlDatabase.existsTable(null, tableName)) {
					File file = new File(fileName);
					if (file.exists()) {
						try {
							if (CREATE.equalsIgnoreCase(updateMode)) {
								sqlDatabase.directLoad(tableName, fileName);
							} else if (UPDATE.equalsIgnoreCase(updateMode)) {
								sqlDatabase.directUpdate(tableName, fileName);
							} else {
								log.error("Update mode '" + updateMode + "' not supported");
							}
							console.print("\n");
						} catch (BroadSQLException se) {
							console.error(se);
							console.println("");
						}
					} else {
						log.error("File '" + fileName + "' not found");
					}
				} else {
					log.error("Table '" + tableName + "' not found");
				}
			} else {
				log.error("Not enough arguments provided for this command. Please check help");
			}
		}
	}

	@Override
	public String getDescription() {
		return ("Loads records from a CSV file into a table, one query at a time");
	}

	@Override
	public String getArguments() {
		// TODO Auto-generated method stub
		return "LOAD <updateMode> <tableName> <fileName>\n\twhere:\n\t<updateMode> is CREATE or UPDATE\n\t<tableName> is a valid table name \n\t<fileName> is a valid file name";
	}

	@Override
	public String getExamples() {
		// TODO Auto-generated method stub
		return "LOAD CREATE customer c:\\temp\\queries.txt\nLOAD UPDATE customer c:\\temp\\queries.txt";
	}
}
