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
 * Bulk-loads a semicolon-separated CSV file into a table as a single batched transaction:
 * {@code BATCHLOAD <CREATE|UPDATE> <tableName> <fileName>}. All three arguments are mandatory.
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
 * inserted/updated. Unlike {@code LOAD}, this command does not check that {@code tableName} exists
 * before starting.
 */
public class CommandDirectLoadBatch extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandDirectLoadBatch.class);

	static final String UPDATE = "UPDATE";
	static final String CREATE = "CREATE";

	private String tableName;
	private String fileName;
	private String updateMode;

	public CommandDirectLoadBatch() {
		super("BATCHLOAD", "BALO");
	}

	private void getParametersFromQuery(String query) {
		fileName = null;
		tableName = null;
		updateMode = null;

		String[] args = parseArgs(query);
		log.debug("args: {}", args);
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
		if (query != null && !query.trim().equals("")) {
			query = query.toUpperCase();
			//log.debug("Query="+query);
			getParametersFromQuery(query);
			if (StringUtils.isNotBlank(tableName) && StringUtils.isNotBlank(fileName)) {
				File file = new File(fileName);
				if (file.exists()) {
					try {
						if (CREATE.equalsIgnoreCase(updateMode)) {
							sqlDatabase.directLoadByBatch(tableName, fileName);
						} else if (UPDATE.equalsIgnoreCase(updateMode)) {
							sqlDatabase.directUpdateByBatch(tableName, fileName);
						} else {
							log.error("Update mode '" + updateMode + "' not supported");
						}
						console.print("\n");
					} catch (BroadSQLException se) {
						console.error(se);
						console.println("");
					}
				} else {
					log.error("File '" + fileName + "' does not exist");
				}
			} else {
				log.error("You must provide a table name and a file name");
			}
		}
	}

	@Override
	public String getDescription() {
		return ("Loads records from a CSV file into a table using batches of queries.");
	}

	@Override
	public String getArguments() {
		// TODO Auto-generated method stub
		return "BATCHLOAD <updateMode> <tableName> <fileName>\n\twhere:\n\t<updateMode> is CREATE or UPDATE\n\t<tableName> is a valid table name \n\t<fileName> is a valid file name";
	}

	@Override
	public String getExamples() {
		// TODO Auto-generated method stub
		return "BATCHLOAD CREATE customer c:\\temp\\queries.txt\nLOAD UPDATE customer c:\\temp\\queries.txt";
	}

}
