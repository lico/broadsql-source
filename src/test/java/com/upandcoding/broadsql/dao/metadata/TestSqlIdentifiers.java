package com.upandcoding.broadsql.dao.metadata;

import java.lang.reflect.Proxy;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

/**
 * Generated SQL names a resolved table component by component, with the driver's own quote string: never a
 * hard-coded {@code "}, never the whole qualified name inside one pair of quotes.
 */
class TestSqlIdentifiers {

	/** A {@link DatabaseMetaData} answering only the calls {@link SqlIdentifiers} makes. */
	private static DatabaseMetaData driver(Map<String, Object> answers) {
		return (DatabaseMetaData) Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[] { DatabaseMetaData.class },
				(proxy, method, args) -> {
					if (!answers.containsKey(method.getName())) {
						throw new SQLException("unexpected call " + method.getName());
					}
					return answers.get(method.getName());
				});
	}

	private static TableMetadata table(String catalog, String schema, String name) {
		TableMetadata table = new TableMetadata();
		table.setCatalog(catalog);
		table.setSchema(schema);
		table.setName(name);
		return table;
	}

	@Test
	void eachComponentIsQuotedSeparately() throws SQLException {
		DatabaseMetaData standard = driver(Map.of("getIdentifierQuoteString", "\"", "supportsSchemasInDataManipulation", true));
		Assertions.assertEquals("\"Sales\".\"Customer Data\"", SqlIdentifiers.qualifiedName(standard, table("DB", "Sales", "Customer Data")));
	}

	@Test
	void theDriversQuoteStringIsUsedAndAnEmbeddedQuoteIsDoubled() throws SQLException {
		DatabaseMetaData mysql = driver(Map.of("getIdentifierQuoteString", "`", "supportsSchemasInDataManipulation", false,
				"supportsCatalogsInDataManipulation", true, "getCatalogSeparator", ".", "isCatalogAtStart", true));
		Assertions.assertEquals("`shop`.`odd``name`", SqlIdentifiers.qualifiedName(mysql, table("shop", null, "odd`name")));
		Assertions.assertEquals("\"a\"\"b\"", SqlIdentifiers.quote("a\"b", "\""));
	}

	@Test
	void aTableWithoutSchemaOrCatalogIsItsQuotedNameAlone() throws SQLException {
		DatabaseMetaData sqlite = driver(Map.of("getIdentifierQuoteString", "\"", "supportsSchemasInDataManipulation", false,
				"supportsCatalogsInDataManipulation", false));
		Assertions.assertEquals("\"ORDER\"", SqlIdentifiers.qualifiedName(sqlite, table(null, null, "ORDER")));
	}

	@Test
	void aDriverWithoutQuotingGetsTheNameAsIs() throws SQLException {
		DatabaseMetaData unquoted = driver(Map.of("getIdentifierQuoteString", " ", "supportsSchemasInDataManipulation", true));
		Assertions.assertEquals("S.T", SqlIdentifiers.qualifiedName(unquoted, table(null, "S", "T")));
	}
}
