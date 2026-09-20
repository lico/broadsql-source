package com.upandcoding.broadsql.controller.shell.commands.core.set;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.model.metadata.SchemaMetadata;

/**
 * Changes the current schema for the active connection: {@code SET SCHEMA <schemaName>}.
 *
 * <p>{@code schemaName} is mandatory and must be an existing schema of the current connection
 * (matched case-insensitively); the command fails with an error if it is missing or unknown. Once
 * set, unqualified table names in subsequent SQL statements resolve against this schema for the rest
 * of the session (until changed again or the connection is closed).
 */
public class CommandSetSchema extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandSetSchema.class);

	public CommandSetSchema() {
		super("SET SCHEMA", "SE SC", "SESC");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		
		String[] args = parseArgs(query);

		String schemaName = null;
		if (CommandUtils.isValidArgs(args)) {
			schemaName = args[0].trim();
			Set<SchemaMetadata> schemas = sqlDatabase.getSchemas(null);
			boolean found = false;
			for (SchemaMetadata sc : schemas) {
				if (sc.getName().equalsIgnoreCase(schemaName)) {
					found = true;
					break;
				}
			}
			if (found) {
				sqlDatabase.setSchema(schemaName);
			} else {
				console.error("Schema '" + schemaName + "' does not exist");
			}
		} else {
			console.error("You must specify a valid schema name");
		}

	}

	@Override
	public boolean isHidden() {
		return (false);
	}

	@Override
	public String getDescription() {
		return ("Select a different schema");
	}

	@Override
	public String getArguments() {
		return "<schemaName> (mandatory) a valid schema name";
	}

	@Override
	public String getExamples() {
		return "SET SCHEMA INFORMATION_SCHEMA;";
	}
}
