package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.metadata.SchemaMetadata;

/**
 * Lists the schemas available in the current database: {@code SHOW SCHEMAS}, flagging which one is
 * the connection's current schema.
 *
 * <p>{@code schemaName} is optional and acts as a case-insensitive "contains" filter (a plain
 * substring search, not a {@code %}-wildcard pattern): only schemas whose name includes it are
 * listed. With no argument, every schema is listed.
 */
public class CommandShowSchemas extends Command {

	private final static Logger log = LoggerFactory.getLogger(CommandShowSchemas.class.getName());

	public CommandShowSchemas() {
		super("SHOW SCHEMAS", "SH SC", "SHSC");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		String schemasNameFilter = null;
		if (CommandUtils.isValidArgs(args)) {
			schemasNameFilter = args[0].trim();
			if (StringUtils.isBlank(schemasNameFilter)) {
				schemasNameFilter = null;
			}
		}
		
		try {
			TreeSet<SchemaMetadata> schemas = sqlDatabase.getSchemas(schemasNameFilter);
			shellConsolePrinter.printSchemas(schemas);
		} catch (BroadSQLException se) {
			throw new BroadSQLException(se);
		}
	}

	@Override
	public String getDescription() {
		return ("Displays a list of schemas in the current database");
	}

	@Override
	public String getArguments() {
		return "<schemaName> (optional) a search text, can be a schema name or a part of it";
	}

	@Override
	public String getExamples() {
		return "SHOW SCHEMAS;\treturns all schemas in current database\n\tSHOW SCHEMAS MYSC;\treturns all schemas which name contains string MYSC, ignoring case";
	}
}
