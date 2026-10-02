package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.upandcoding.broadsql.dao.metadata.MetadataException;
import com.upandcoding.broadsql.dao.metadata.MetadataService;
import com.upandcoding.broadsql.dao.model.metadata.ForeignKeyMetadata;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

/**
 * Lists the foreign keys of other tables that reference a table: {@code SHOW REFERENCES <table>}.
 *
 * <p>The inverse of {@code SHOW FK}: one row per column of each foreign key pointing to this table, with
 * the referencing table, the constraint name, the column position within the key ({@code #}), the
 * referencing column and the column of this table it references. A referencing table in another schema is
 * shown qualified. A table that references itself appears too.
 *
 * <p>Table names are resolved as for {@code SHOW FK}. Reports that nothing references the table when
 * no foreign key does. Fails with an error if no table name is given, if the table does not exist or is
 * ambiguous, or if the JDBC driver cannot provide foreign key metadata.
 */
public class CommandShowReferences extends TableMetadataCommand {

	public CommandShowReferences() {
		super("SHOW REFERENCES", "SHOW REFS", "SH REF", "SHREF");
	}

	@Override
	protected void show(MetadataService metadata, TableMetadata table) throws MetadataException {
		List<ForeignKeyMetadata> keys = metadata.references(table);
		if (keys.isEmpty()) {
			console.info("No foreign key references table " + displayName(table) + ".");
			return;
		}
		List<List<String>> rows = new ArrayList<>();
		for (ForeignKeyMetadata fk : keys) {
			rows.add(Arrays.asList(relativeName(fk.getFkCatalog(), fk.getFkSchema(), fk.getFkTable(), table.getSchema()), fk.getFkName(),
					String.valueOf(fk.getKeySeq()), fk.getFkColumn(), fk.getPkColumn()));
		}
		shellConsolePrinter.printTable(List.of("REFERENCING_TABLE", "FK_NAME", "#", "REFERENCING_COLUMN", "REFERENCED_COLUMN"), rows);
	}

	@Override
	public String getDescription() {
		return "Shows the foreign keys of other tables that reference a table";
	}

	@Override
	public String getArguments() {
		return "<table> (mandatory) a table name, optionally qualified with its schema";
	}

	@Override
	public String getExamples() {
		return "SHOW REFERENCES CUSTOMER;";
	}
}
