package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.List;

import com.upandcoding.broadsql.dao.metadata.MetadataException;
import com.upandcoding.broadsql.dao.metadata.MetadataService;
import com.upandcoding.broadsql.dao.model.metadata.PrimaryKeyMetadata;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

/**
 * Lists the primary key columns of a table: {@code SHOW PK <tableName>}.
 *
 * <p>{@code tableName} is mandatory and may be schema-qualified (e.g. {@code SHOW PK
 * PUBLIC.CUSTOMER}). Table names are resolved exactly as for {@code DESCR}: schema-qualified or current
 * schema, tried as typed, then upper case, then lower case, and otherwise matched exactly ({@code _} and
 * {@code %} are ordinary characters of the name, never wildcards).
 *
 * <p>Reports that the table has no primary key when it has none. Fails with an error if no table name is
 * given, if the table does not exist or is ambiguous, or if the JDBC driver cannot provide primary key
 * metadata.
 */
public class CommandShowPrimaryKeys extends TableMetadataCommand {

	public CommandShowPrimaryKeys() {
		super("SHOW PK", "SHOW PRIMARY KEYS", "SH PK", "SHPK");
	}

	@Override
	protected void show(MetadataService metadata, TableMetadata table) throws MetadataException {
		List<PrimaryKeyMetadata> keys = metadata.primaryKeys(table);
		if (keys.isEmpty()) {
			console.info("Table " + displayName(table) + " has no primary key.");
			return;
		}
		shellConsolePrinter.printPrimaryKeys(keys);
	}

	@Override
	public String getDescription() {
		return ("Shows the primary keys for a given table name");
	}

	@Override
	public String getArguments() {
		return "<tableName> (mandatory) a table name, optionally qualified with its schema";
	}

	@Override
	public String getExamples() {
		return "SHOW PK CUSTOMER;";
	}
}
