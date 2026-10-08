package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.metadata.MetadataException;
import com.upandcoding.broadsql.dao.metadata.MetadataService;
import com.upandcoding.broadsql.dao.model.metadata.ColumnMetadata;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

/**
 * Describes the structure of a table: {@code DESCR <tableName> [ALPHA]}, listing each column's name, type,
 * size, nullability and default value. By default the columns are listed in the order of the columns in
 * the table (their position in the table as the database reports it). With {@code ALPHA}, the same
 * columns, with the same information, are listed sorted by column name, ignoring case, for quicker
 * lookup in a wide table; only the order of the rows changes.
 *
 * <p>Size is the declared length for character and binary columns, and {@code precision,scale} for
 * {@code DECIMAL} and {@code NUMERIC} columns (for example {@code 10,2}); for other types it is the
 * size the JDBC driver reports.
 *
 * <p>{@code tableName} is mandatory and may be schema-qualified (e.g. {@code DESCR PUBLIC.CUSTOMER}).
 * When no schema is given, only the connection's current schema is searched, and only that schema's
 * columns are listed; a table with the same name in another schema is not shown. Qualify the name
 * (e.g. {@code DESCR OTHERSCHEMA.TOTO}) to describe a table in a different schema. It is looked up
 * case-insensitively: the name is tried as typed, then upper-cased, then lower-cased, and the first
 * variant that resolves to an existing table is used; if that variant isn't the one you typed, the
 * console reports which name was actually matched. There is no wildcard or partial-name support: the
 * (case-insensitive) table name must otherwise match exactly, and {@code _} and {@code %} are ordinary
 * characters of the name ({@code DESCR FOO_BAR} never describes {@code FOOXBAR}, and {@code DESCR TRY}
 * never describes {@code COUNTRY}). {@code SHOW PK}, {@code SHOW FK}, {@code SHOW REFERENCES} and
 * {@code SHOW INDEXES} resolve table names exactly the same way.
 *
 * <p>Fails with an error if no table name is given, if anything other than {@code ALPHA} follows the
 * table name, if none of the three case variants resolves to an existing table, or if the name matches
 * tables in several schemas.
 */
public class CommandDescr extends TableMetadataCommand {

	/** The one option accepted after the table name. */
	static final String ALPHA = "ALPHA";

	private static final Comparator<ColumnMetadata> BY_NAME_IGNORING_CASE =
			Comparator.comparing(ColumnMetadata::getName, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));

	/** Set by {@link #execute} for the current invocation only. */
	private boolean alphabetical;

	public CommandDescr() {
		super("DESCR", "DESC", "DESCRIBE");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		alphabetical = args != null && args.length == 2 && ALPHA.equalsIgnoreCase(args[1].trim());
		if (args != null && args.length > 1 && !alphabetical) {
			console.error("Unexpected text after the table name: '" + String.join(" ", List.of(args).subList(1, args.length))
					+ "'. Usage: DESCR <tableName> [ALPHA]");
			return;
		}
		super.execute(query);
	}

	@Override
	protected void show(MetadataService metadata, TableMetadata table) throws MetadataException {
		List<ColumnMetadata> columns = metadata.columns(table);
		if (columns.isEmpty()) {
			console.info("Table " + displayName(table) + " has no columns.");
			return;
		}
		if (alphabetical) {
			columns = new ArrayList<>(columns);
			columns.sort(BY_NAME_IGNORING_CASE);
		}
		shellConsolePrinter.printColumns(columns);
	}

	@Override
	public String getDescription() {
		return ("Describes the structure of the table");
	}

	@Override
	public String getArguments() {
		return "<tableName> (mandatory) the name of a table; "
				+ "ALPHA (optional) lists the columns sorted by name, ignoring case, instead of in table order";
	}

	@Override
	public String getExamples() {
		return "DESCR CUSTOMER;\n\tDESC CUSTOMER ALPHA;";
	}
}
