package com.upandcoding.broadsql.dao.metadata;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.h2.tools.SimpleResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.model.metadata.ColumnMetadata;
import com.upandcoding.broadsql.dao.model.metadata.ForeignKeyMetadata;
import com.upandcoding.broadsql.dao.model.metadata.IndexMetadata;
import com.upandcoding.broadsql.dao.model.metadata.TableMetadata;

/**
 * SPRINT 2409K: {@link MetadataService} against a real H2 schema, plus JDBC proxies for the cases H2 itself
 * cannot produce (no current schema, a driver without foreign key support, statistics rows from
 * {@code getIndexInfo}). Every assertion is on normalized order, never on the driver's own ordering.
 */
class TestMetadataService {

	static final String[] SCHEMA = {
			"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(80) NOT NULL, EMAIL VARCHAR(120), REGION CHAR(2) DEFAULT 'EU', BALANCE DECIMAL(10,2))",
			"CREATE UNIQUE INDEX UX_CUSTOMER_EMAIL ON CUSTOMER(EMAIL)",
			"CREATE TABLE PRODUCT (CODE VARCHAR(10), VARIANT INT, LABEL VARCHAR(50), CONSTRAINT PK_PRODUCT PRIMARY KEY (CODE, VARIANT))",
			"CREATE TABLE ORDERS (ID INT PRIMARY KEY, CUSTOMER_ID INT NOT NULL, AMOUNT DECIMAL(12,2), "
					+ "CONSTRAINT FK_ORDER_CUST FOREIGN KEY (CUSTOMER_ID) REFERENCES CUSTOMER(ID))",
			"CREATE INDEX IX_ORDERS_CUST_AMOUNT ON ORDERS(CUSTOMER_ID, AMOUNT)",
			// the composite FK lists its columns in the opposite order of the referenced primary key
			"CREATE TABLE ORDER_LINE (ORDER_ID INT, LINE_NO INT, PRODUCT_VARIANT INT, PRODUCT_CODE VARCHAR(10), PRIMARY KEY (ORDER_ID, LINE_NO), "
					+ "CONSTRAINT FK_LINE_ORDER FOREIGN KEY (ORDER_ID) REFERENCES ORDERS(ID), "
					+ "CONSTRAINT FK_LINE_PRODUCT FOREIGN KEY (PRODUCT_VARIANT, PRODUCT_CODE) REFERENCES PRODUCT(VARIANT, CODE))",
			"CREATE TABLE NOTES (TXT VARCHAR(10))",
			"CREATE SCHEMA SALES",
			"CREATE TABLE SALES.TARGET (ID INT PRIMARY KEY, CUSTOMER_ID INT, CONSTRAINT FK_TARGET_CUST FOREIGN KEY (CUSTOMER_ID) REFERENCES PUBLIC.CUSTOMER(ID))",
			"CREATE TABLE SALES.ORDERS (ID INT)",
			"CREATE TABLE \"MixedCase\" (ID INT)" };

	private Connection connection;
	private MetadataService service;

	static Connection newDatabase() throws Exception {
		Connection c = DriverManager.getConnection("jdbc:h2:mem:meta_" + UUID.randomUUID().toString().replace("-", ""));
		try (Statement st = c.createStatement()) {
			for (String sql : SCHEMA) {
				st.execute(sql);
			}
		}
		return c;
	}

	@BeforeEach
	void setUp() throws Exception {
		connection = newDatabase();
		service = new MetadataService(connection);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (!connection.isClosed()) {
			connection.close();
		}
	}

	private static List<String> names(List<ColumnMetadata> columns) {
		List<String> names = new ArrayList<>();
		columns.forEach(c -> names.add(c.getName()));
		return names;
	}

	// ---- resolution ----

	/**
	 * The table category of SHOW TABLES, LOAD and FIND, over the type vocabularies drivers report (H2, pgjdbc,
	 * Oracle, MySQL, HSQLDB, Derby): real tables only, never a view, synonym, index, sequence, type or TOAST table.
	 */
	@Test
	void onlyTableTypesBelongToTheTableCategory() {
		for (String table : new String[] { "TABLE", "BASE TABLE", "PARTITIONED TABLE", "FOREIGN TABLE", "TEMPORARY TABLE", "GLOBAL TEMPORARY",
				"LOCAL TEMPORARY" }) {
			Assertions.assertTrue(MetadataService.isTableType(table, false), table);
		}
		for (String other : new String[] { "VIEW", "MATERIALIZED VIEW", "SYSTEM VIEW", "TEMPORARY VIEW", "SYNONYM", "ALIAS", "INDEX",
				"PARTITIONED INDEX", "TEMPORARY INDEX", "SEQUENCE", "TEMPORARY SEQUENCE", "TYPE", "SYSTEM TOAST TABLE", "TEMPORARY TOAST TABLE" }) {
			Assertions.assertFalse(MetadataService.isTableType(other, true), other);
		}
		Assertions.assertTrue(MetadataService.isTableType("SYSTEM TABLE", true), "a system table, when asked for");
		Assertions.assertFalse(MetadataService.isTableType("SYSTEM TABLE", false), "not in FIND's scope");
	}

	@Test
	void unqualifiedNamesResolveInTheCurrentSchemaAndIgnoreCase() throws Exception {
		TableMetadata t = service.resolveTable("orders");
		Assertions.assertEquals("PUBLIC", t.getSchema());
		Assertions.assertEquals("ORDERS", t.getName());
	}

	@Test
	void qualifiedNamesResolveInTheirSchema() throws Exception {
		TableMetadata t = service.resolveTable("sales.orders");
		Assertions.assertEquals("SALES", t.getSchema());
		Assertions.assertEquals("ORDERS", t.getName());
	}

	@Test
	void anExactMixedCaseNameIsFound() throws Exception {
		Assertions.assertEquals("MixedCase", service.resolveTable("MixedCase").getName());
	}

	@Test
	void resolutionIsExactNotASubstringSearch() {
		// DatabaseConnection.existsTable("ORDER") would be true (it searches %ORDER%)
		MetadataException e = Assertions.assertThrows(MetadataException.class, () -> service.resolveTable("ORDER"));
		Assertions.assertEquals(MetadataException.Kind.NOT_FOUND, e.getKind());
	}

	@Test
	void jdbcWildcardsInANameAreLiteral() {
		// "ORDER_" would match ORDERS as a JDBC pattern
		MetadataException e = Assertions.assertThrows(MetadataException.class, () -> service.resolveTable("ORDER_"));
		Assertions.assertEquals(MetadataException.Kind.NOT_FOUND, e.getKind());
	}

	@Test
	void aMissingTableIsNotFound() {
		MetadataException e = Assertions.assertThrows(MetadataException.class, () -> service.resolveTable("NO_SUCH_TABLE"));
		Assertions.assertEquals(MetadataException.Kind.NOT_FOUND, e.getKind());
		Assertions.assertTrue(e.getMessage().contains("NO_SUCH_TABLE"), e.getMessage());
		Assertions.assertEquals(MetadataException.Kind.NOT_FOUND,
				Assertions.assertThrows(MetadataException.class, () -> service.resolveTable("SALES.CUSTOMER")).getKind());
	}

	@Test
	void aNameInSeveralSchemasIsAmbiguousWhenTheDriverReportsNoCurrentSchema() {
		MetadataService noSchema = new MetadataService(proxy(connection, Connection.class, name -> name.equals("getSchema") ? NULL : null));
		MetadataException e = Assertions.assertThrows(MetadataException.class, () -> noSchema.resolveTable("ORDERS"));
		Assertions.assertEquals(MetadataException.Kind.AMBIGUOUS, e.getKind());
		Assertions.assertTrue(e.getMessage().contains("PUBLIC.ORDERS") && e.getMessage().contains("SALES.ORDERS"), e.getMessage());
	}

	// ---- columns ----

	@Test
	void columnsComeInTableOrderWithTypeSizeNullabilityAndDefault() throws Exception {
		List<ColumnMetadata> columns = service.columns(service.resolveTable("CUSTOMER"));
		Assertions.assertEquals(List.of("ID", "NAME", "EMAIL", "REGION", "BALANCE"), names(columns));
		ColumnMetadata name = columns.get(1);
		Assertions.assertEquals(DatabaseMetaData.columnNoNulls, name.getNullable());
		Assertions.assertEquals(80, name.getColumnSize());
		Assertions.assertEquals(DatabaseMetaData.columnNullable, columns.get(2).getNullable());
		Assertions.assertTrue(columns.get(3).getDefaultValue().contains("EU"), columns.get(3).getDefaultValue());
		Assertions.assertEquals(Types.DECIMAL, columns.get(4).getDataType());
		Assertions.assertEquals(10, columns.get(4).getColumnSize());
		Assertions.assertEquals(2, columns.get(4).getDecimalDigit());
	}

	@Test
	void columnsOfAQualifiedTableAreOnlyThatTables() throws Exception {
		Assertions.assertEquals(List.of("ID"), names(service.columns(service.resolveTable("SALES.ORDERS"))));
		Assertions.assertEquals(List.of("ID", "CUSTOMER_ID", "AMOUNT"), names(service.columns(service.resolveTable("ORDERS"))));
	}

	// ---- SHOW FK / SHOW REFERENCES ----

	@Test
	void foreignKeysOfATable() throws Exception {
		List<ForeignKeyMetadata> fks = service.foreignKeys(service.resolveTable("ORDERS"));
		Assertions.assertEquals(1, fks.size());
		ForeignKeyMetadata fk = fks.get(0);
		Assertions.assertEquals("FK_ORDER_CUST", fk.getFkName());
		Assertions.assertEquals("CUSTOMER_ID", fk.getFkColumn());
		Assertions.assertEquals("CUSTOMER", fk.getPkTable());
		Assertions.assertEquals("ID", fk.getPkColumn());
	}

	@Test
	void aCompositeForeignKeyKeepsItsDeclaredColumnOrder() throws Exception {
		List<ForeignKeyMetadata> fks = service.foreignKeys(service.resolveTable("ORDER_LINE"));
		List<String> rows = new ArrayList<>();
		fks.forEach(fk -> rows.add(fk.getFkName() + ":" + fk.getKeySeq() + ":" + fk.getFkColumn() + "->" + fk.getPkTable() + "." + fk.getPkColumn()));
		Assertions.assertEquals(List.of("FK_LINE_ORDER:1:ORDER_ID->ORDERS.ID", "FK_LINE_PRODUCT:1:PRODUCT_VARIANT->PRODUCT.VARIANT",
				"FK_LINE_PRODUCT:2:PRODUCT_CODE->PRODUCT.CODE"), rows);
	}

	@Test
	void aTableWithoutForeignKeysIsAnEmptyResultNotAnError() throws Exception {
		Assertions.assertTrue(service.foreignKeys(service.resolveTable("NOTES")).isEmpty());
		Assertions.assertTrue(service.references(service.resolveTable("NOTES")).isEmpty());
	}

	@Test
	void incomingReferencesIncludeOtherSchemas() throws Exception {
		List<ForeignKeyMetadata> refs = service.references(service.resolveTable("CUSTOMER"));
		List<String> rows = new ArrayList<>();
		refs.forEach(fk -> rows.add(fk.getFkSchema() + "." + fk.getFkTable() + ":" + fk.getFkName() + ":" + fk.getFkColumn() + "->" + fk.getPkColumn()));
		Assertions.assertEquals(List.of("PUBLIC.ORDERS:FK_ORDER_CUST:CUSTOMER_ID->ID", "SALES.TARGET:FK_TARGET_CUST:CUSTOMER_ID->ID"), rows);
	}

	@Test
	void incomingReferencesOfACompositeKey() throws Exception {
		List<ForeignKeyMetadata> refs = service.references(service.resolveTable("PRODUCT"));
		Assertions.assertEquals(2, refs.size());
		Assertions.assertEquals("PRODUCT_VARIANT", refs.get(0).getFkColumn());
		Assertions.assertEquals("PRODUCT_CODE", refs.get(1).getFkColumn());
	}

	@Test
	void aDriverWithoutForeignKeySupportIsReportedAsUnsupported() throws Exception {
		DatabaseMetaData realMeta = connection.getMetaData();
		DatabaseMetaData noFk = proxy(realMeta, DatabaseMetaData.class, name -> {
			if (name.equals("getImportedKeys") || name.equals("getExportedKeys")) {
				return new SQLFeatureNotSupportedException("no FK");
			}
			return null;
		});
		MetadataService limited = new MetadataService(proxy(connection, Connection.class, name -> name.equals("getMetaData") ? noFk : null));
		TableMetadata orders = limited.resolveTable("ORDERS");
		Assertions.assertEquals(MetadataException.Kind.UNSUPPORTED, Assertions.assertThrows(MetadataException.class, () -> limited.foreignKeys(orders)).getKind());
		Assertions.assertEquals(MetadataException.Kind.UNSUPPORTED, Assertions.assertThrows(MetadataException.class, () -> limited.references(orders)).getKind());
	}

	@Test
	void aFailingRetrievalIsNotAnEmptyResult() throws Exception {
		TableMetadata orders = service.resolveTable("ORDERS");
		connection.close();
		Assertions.assertEquals(MetadataException.Kind.RETRIEVAL_FAILED,
				Assertions.assertThrows(MetadataException.class, () -> service.foreignKeys(orders)).getKind());
	}

	// ---- SHOW INDEXES ----

	@Test
	void uniqueNonUniqueAndCompositeIndexes() throws Exception {
		List<String> customer = new ArrayList<>();
		service.indexes(service.resolveTable("CUSTOMER")).forEach(i -> customer.add(i.getIndexName() + ":" + i.getOrdinalPosition() + ":" + i.getColumn() + ":" + i.isUnique()));
		Assertions.assertTrue(customer.contains("UX_CUSTOMER_EMAIL:1:EMAIL:true"), customer.toString());

		List<IndexMetadata> orders = service.indexes(service.resolveTable("ORDERS"));
		List<String> composite = new ArrayList<>();
		orders.stream().filter(i -> i.getIndexName().equals("IX_ORDERS_CUST_AMOUNT"))
				.forEach(i -> composite.add(i.getOrdinalPosition() + ":" + i.getColumn() + ":" + i.isUnique()));
		Assertions.assertEquals(List.of("1:CUSTOMER_ID:false", "2:AMOUNT:false"), composite);
	}

	@Test
	void aTableWithoutIndexesIsAnEmptyResult() throws Exception {
		Assertions.assertTrue(service.indexes(service.resolveTable("NOTES")).isEmpty());
	}

	@Test
	void statisticsRowsAreNotShownAsIndexes() throws Exception {
		DatabaseMetaData realMeta = connection.getMetaData();
		DatabaseMetaData withStats = proxy(realMeta, DatabaseMetaData.class, name -> {
			if (!name.equals("getIndexInfo")) {
				return null;
			}
			SimpleResultSet rs = new SimpleResultSet();
			for (String col : new String[] { "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "INDEX_NAME", "COLUMN_NAME" }) {
				rs.addColumn(col, Types.VARCHAR, 100, 0);
			}
			rs.addColumn("TYPE", Types.SMALLINT, 5, 0);
			rs.addColumn("ORDINAL_POSITION", Types.INTEGER, 10, 0);
			rs.addColumn("NON_UNIQUE", Types.BOOLEAN, 1, 0);
			rs.addRow(null, "PUBLIC", "T", null, null, DatabaseMetaData.tableIndexStatistic, 0, false);
			rs.addRow(null, "PUBLIC", "T", "IX_B", "B", DatabaseMetaData.tableIndexOther, 2, true);
			rs.addRow(null, "PUBLIC", "T", "IX_B", "A", DatabaseMetaData.tableIndexOther, 1, true);
			return rs;
		});
		MetadataService stats = new MetadataService(proxy(connection, Connection.class, name -> name.equals("getMetaData") ? withStats : null));
		TableMetadata t = new TableMetadata();
		t.setSchema("PUBLIC");
		t.setName("T");
		List<IndexMetadata> indexes = stats.indexes(t);
		Assertions.assertEquals(2, indexes.size());
		Assertions.assertEquals("A", indexes.get(0).getColumn());
		Assertions.assertEquals("B", indexes.get(1).getColumn());
	}

	// ---- FIND ----

	@Test
	void findForeignKeysMatchesNamesTablesAndColumnsAndReturnsWholeCompositeKeys() throws Exception {
		List<String> byColumn = new ArrayList<>();
		service.findForeignKeys("variant").forEach(fk -> byColumn.add(fk.getFkName() + ":" + fk.getKeySeq()));
		Assertions.assertEquals(List.of("FK_LINE_PRODUCT:1", "FK_LINE_PRODUCT:2"), byColumn);

		List<String> byReferencedTable = new ArrayList<>();
		service.findForeignKeys("customer").forEach(fk -> byReferencedTable.add(fk.getFkName()));
		// SALES.TARGET is outside the current schema: FIND searches the current schema
		Assertions.assertEquals(List.of("FK_ORDER_CUST"), byReferencedTable);

		Assertions.assertEquals(2, service.findForeignKeys("FK_LINE%").stream().map(ForeignKeyMetadata::getFkName).distinct().count());
		Assertions.assertTrue(service.findForeignKeys("nothing_like_this").isEmpty());
	}

	@Test
	void findIndexesMatchesIndexTableAndColumnNames() throws Exception {
		List<String> byName = new ArrayList<>();
		service.findIndexes("ix_orders").forEach(i -> byName.add(i.getIndexName() + ":" + i.getOrdinalPosition() + ":" + i.getColumn()));
		Assertions.assertEquals(List.of("IX_ORDERS_CUST_AMOUNT:1:CUSTOMER_ID", "IX_ORDERS_CUST_AMOUNT:2:AMOUNT"), byName);

		Assertions.assertTrue(service.findIndexes("email").stream().anyMatch(i -> i.getIndexName().equals("UX_CUSTOMER_EMAIL") && i.isUnique()));
		// matching one column of a composite index returns the whole index
		Assertions.assertEquals(2, service.findIndexes("amount").stream().filter(i -> i.getIndexName().equals("IX_ORDERS_CUST_AMOUNT")).count());
		Assertions.assertTrue(service.findIndexes("nothing_like_this").isEmpty());
	}

	@Test
	void theSearchPatternFollowsFindColumnConventions() {
		Assertions.assertTrue(MetadataService.searchPattern("cust").matcher("FK_ORDER_CUST").matches());
		Assertions.assertTrue(MetadataService.searchPattern("FK%CUST").matcher("FK_ORDER_CUST").matches());
		Assertions.assertTrue(MetadataService.searchPattern("FK_O").matcher("FKXORDER").matches());
		Assertions.assertFalse(MetadataService.searchPattern("a.b").matcher("AXB").matches());
	}

	// ---- helpers ----

	private static final Object NULL = new Object();

	/**
	 * A proxy of {@code target} where {@code override} may replace a method's result: {@code null} delegates,
	 * {@link #NULL} returns {@code null}, a {@link Throwable} is thrown, anything else is returned.
	 */
	@SuppressWarnings("unchecked")
	static <T> T proxy(T target, Class<T> type, Function<String, Object> override) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (p, method, args) -> {
			Object replaced = override.apply(method.getName());
			if (replaced == NULL) {
				return null;
			}
			if (replaced instanceof Throwable) {
				throw (Throwable) replaced;
			}
			if (replaced != null) {
				return replaced;
			}
			try {
				return method.invoke(target, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		});
	}
}
