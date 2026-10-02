package com.upandcoding.broadsql.dao.metadata;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

/**
 * Provider-aware quoting of JDBC database identifiers in SQL that BroadSQL generates. Once a table has been
 * resolved through metadata ({@link MetadataService#resolveTable}), the SQL that reads it must name exactly that
 * table: each component (catalog, schema, table) is quoted separately with the driver's own quote string
 * ({@link DatabaseMetaData#getIdentifierQuoteString()}: {@code "} for most databases, a backtick for MySQL and
 * MariaDB), with any embedded quote character doubled. A quoted identifier keeps its exact case, spaces and
 * reserved words, so {@code "Customer"} can never be read as {@code CUSTOMER}, and {@code "Sales Data"} never as
 * table {@code SALES} with alias {@code Data}.
 *
 * <p>A driver that reports a blank quote string (quoting unsupported) gets the identifier as is.
 */
public final class SqlIdentifiers {

	private SqlIdentifiers() {
	}

	/** {@code identifier} quoted with {@code quoteString}, embedded quote strings doubled; unchanged when quoting is unsupported. */
	public static String quote(String identifier, String quoteString) {
		if (StringUtils.isBlank(quoteString)) {
			return identifier;
		}
		String q = quoteString.trim();
		return q + identifier.replace(q, q + q) + q;
	}

	/**
	 * The SQL reference to a resolved table: {@code schema.table} when the table has a schema and the driver accepts
	 * schemas in data manipulation statements, otherwise {@code catalog.table} (with the driver's catalog separator
	 * and position) when it has a catalog and the driver accepts catalogs there, otherwise the table alone. Every
	 * component is quoted separately ({@link #quote}).
	 */
	public static String qualifiedName(DatabaseMetaData dbmd, TableMetadata table) throws SQLException {
		String quoteString = identifierQuoteString(dbmd);
		String name = quote(table.getName(), quoteString);
		if (StringUtils.isNotBlank(table.getSchema()) && supports(() -> dbmd.supportsSchemasInDataManipulation(), true)) {
			return quote(table.getSchema(), quoteString) + "." + name;
		}
		if (StringUtils.isNotBlank(table.getCatalog()) && supports(() -> dbmd.supportsCatalogsInDataManipulation(), false)) {
			String separator = StringUtils.defaultIfEmpty(dbmd.getCatalogSeparator(), ".");
			String catalog = quote(table.getCatalog(), quoteString);
			return supports(() -> dbmd.isCatalogAtStart(), true) ? catalog + separator + name : name + separator + catalog;
		}
		return name;
	}

	private static String identifierQuoteString(DatabaseMetaData dbmd) throws SQLException {
		try {
			return dbmd.getIdentifierQuoteString();
		} catch (SQLFeatureNotSupportedException | UnsupportedOperationException | AbstractMethodError e) {
			return null;
		}
	}

	private interface Capability {
		boolean get() throws SQLException;
	}

	private static boolean supports(Capability capability, boolean whenUnknown) throws SQLException {
		try {
			return capability.get();
		} catch (SQLFeatureNotSupportedException | UnsupportedOperationException | AbstractMethodError e) {
			return whenUnknown;
		}
	}
}
