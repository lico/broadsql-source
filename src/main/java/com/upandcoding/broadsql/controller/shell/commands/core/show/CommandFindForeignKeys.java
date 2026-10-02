package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.metadata.MetadataException;
import com.upandcoding.broadsql.dao.model.metadata.ForeignKeyMetadata;

/**
 * Finds foreign keys by name, table or column: {@code FIND FK <text>}, also available as
 * {@code FIND REFERENCE <text>}.
 *
 * <p>Lists every foreign key of the current schema whose constraint name, referencing table, referencing
 * column, referenced table or referenced column contains the text. Matching ignores case, and, as with
 * {@code FIND COLUMN}, {@code %} matches any sequence of characters and {@code _} any single character. One
 * row per column pair, with the key position ({@code #}); every column of a matching composite foreign key
 * is listed, even when only one of them matched.
 *
 * <p>Uses the same metadata as {@code SHOW FK} and {@code SHOW REFERENCES}, read for each table of the
 * current schema, so a large schema takes a moment. Fails with an error if no text is given or if the JDBC
 * driver cannot provide foreign key metadata.
 */
public class CommandFindForeignKeys extends Command {

	public CommandFindForeignKeys() {
		super("FIND FK", "FIND REFERENCE", "FIND REFERENCES", "FI FK", "FIFK");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		String text = CommandUtils.isValidArgs(args) ? args[0].trim() : null;
		if (StringUtils.isBlank(text)) {
			console.error("You must provide a text to search for");
			return;
		}
		if (sqlDatabase == null) {
			console.error("Not connected to a database");
			return;
		}
		try {
			List<ForeignKeyMetadata> keys = sqlDatabase.getMetadataService().findForeignKeys(text);
			if (keys.isEmpty()) {
				console.info("No foreign key matches '" + text + "' in the current schema.");
				return;
			}
			List<List<String>> rows = new ArrayList<>();
			for (ForeignKeyMetadata fk : keys) {
				rows.add(Arrays.asList(fk.getFkName(), fk.getFkTable(), String.valueOf(fk.getKeySeq()), fk.getFkColumn(),
						TableMetadataCommand.relativeName(fk.getPkCatalog(), fk.getPkSchema(), fk.getPkTable(), fk.getFkSchema()), fk.getPkColumn()));
			}
			shellConsolePrinter.printTable(List.of("FK_NAME", "TABLE", "#", "COLUMN", "REFERENCES_TABLE", "REFERENCES_COLUMN"), rows);
		} catch (MetadataException e) {
			console.error(e.getMessage());
		}
	}

	@Override
	public String getDescription() {
		return "Finds foreign keys whose name, tables or columns contain the input text";
	}

	@Override
	public String getArguments() {
		return "<text> (mandatory) part of a constraint, table or column name";
	}

	@Override
	public String getExamples() {
		return "FIND FK CUSTOMER;";
	}
}
