package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLErrorMessages;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import com.upandcoding.broadsql.dao.metadata.MetadataException;
import com.upandcoding.broadsql.dao.metadata.MetadataService;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

/**
 * SPRINT 2409K: shared behavior of the portable table-metadata commands ({@code DESCR}, {@code SHOW PK},
 * {@code SHOW FK}, {@code SHOW REFERENCES}, {@code SHOW INDEXES}): the one table-name argument, its
 * resolution through the canonical exact resolver {@link MetadataService#resolveTable} (so the same typed
 * name resolves to the same table, or fails the same way, in every one of them), and the reporting of a
 * lookup that produced no result (invalid name, not found, ambiguous, unsupported by the driver, failed) as
 * an error rather than an empty grid. Not a command itself: abstract, so the command loader and
 * documentation generator skip it.
 */
public abstract class TableMetadataCommand extends Command {

	protected TableMetadataCommand(String... keywords) {
		super(keywords);
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		String name = CommandUtils.isValidArgs(args) ? args[0].trim() : null;
		if (StringUtils.isBlank(name)) {
			console.error(BroadSQLErrorMessages.ERR_GAL_01);
			return;
		}
		if (sqlDatabase == null) {
			console.error("Not connected to a database");
			return;
		}
		MetadataService metadata = sqlDatabase.getMetadataService();
		try {
			TableMetadata table = metadata.resolveTable(name);
			boolean qualified = name.contains(".");
			String typedTable = qualified ? StringUtils.substringAfterLast(name, ".").trim() : name;
			if (!typedTable.equals(table.getName())) {
				// A case variant of the typed name was used: say which table, in the form it was typed
				console.writeln("Table '" + name + "' not found. Found table '" + (qualified ? displayName(table) : table.getName()) + "' instead.");
			}
			show(metadata, table);
		} catch (MetadataException e) {
			console.error(e.getMessage());
		}
	}

	/** Prints the metadata of an existing, resolved table; an empty result must be reported as such, never as an empty grid. */
	protected abstract void show(MetadataService metadata, TableMetadata table) throws MetadataException;

	/** {@code SCHEMA.TABLE}, or just the table when the driver reports no schema (catalog-based databases show {@code CATALOG.TABLE}). */
	protected static String displayName(TableMetadata table) {
		return qualified(table.getCatalog(), table.getSchema(), table.getName());
	}

	protected static String qualified(String catalog, String schema, String name) {
		String owner = StringUtils.isNotBlank(schema) ? schema : catalog;
		return StringUtils.isNotBlank(owner) ? owner + "." + name : name;
	}

	/** {@code name} alone when its owner is {@code contextSchema}, qualified otherwise. */
	protected static String relativeName(String catalog, String schema, String name, String contextSchema) {
		if (StringUtils.isNotBlank(schema) ? StringUtils.equals(schema, contextSchema) : StringUtils.isBlank(catalog) || StringUtils.isBlank(contextSchema)) {
			return name;
		}
		return qualified(catalog, schema, name);
	}

	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.TABLE);
	}
}
