package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.upandcoding.broadsql.dao.metadata.MetadataException;
import com.upandcoding.broadsql.dao.metadata.MetadataService;
import com.upandcoding.broadsql.dao.model.metadata.ForeignKeyMetadata;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

/**
 * Lists the foreign keys a table declares: {@code SHOW FK <table>}.
 *
 * <p>One row per column of each foreign key: the constraint name, the column position within the key
 * ({@code #}), the column of this table, and the table and column it references. The rows of a composite
 * foreign key follow each other in key order. A referenced table in another schema is shown qualified.
 *
 * <p>Use {@code SHOW REFERENCES} for the opposite direction (the foreign keys of other tables that point to
 * this one). Table names are resolved as for {@code DESCR}
 * (schema-qualified or current schema, tried as typed, then upper case, then lower case), and must match exactly.
 *
 * <p>Reports that the table declares no foreign keys when it has none. Fails with an error if no table
 * name is given, if the table does not exist or is ambiguous, or if the JDBC driver cannot provide foreign
 * key metadata.
 */
public class CommandShowForeignKeys extends TableMetadataCommand {

	public CommandShowForeignKeys() {
		super("SHOW FK", "SHOW FOREIGN KEYS", "SH FK", "SHFK");
	}

	@Override
	protected void show(MetadataService metadata, TableMetadata table) throws MetadataException {
		List<ForeignKeyMetadata> keys = metadata.foreignKeys(table);
		if (keys.isEmpty()) {
			console.info("Table " + displayName(table) + " declares no foreign keys.");
			return;
		}
		List<List<String>> rows = new ArrayList<>();
		for (ForeignKeyMetadata fk : keys) {
			rows.add(Arrays.asList(fk.getFkName(), String.valueOf(fk.getKeySeq()), fk.getFkColumn(),
					relativeName(fk.getPkCatalog(), fk.getPkSchema(), fk.getPkTable(), table.getSchema()), fk.getPkColumn()));
		}
		shellConsolePrinter.printTable(List.of("FK_NAME", "#", "COLUMN", "REFERENCES_TABLE", "REFERENCES_COLUMN"), rows);
	}

	@Override
	public String getDescription() {
		return "Shows the foreign keys declared by a table and what they reference";
	}

	@Override
	public String getArguments() {
		return "<table> (mandatory) a table name, optionally qualified with its schema";
	}

	@Override
	public String getExamples() {
		return "SHOW FK ORDERS;";
	}
}
