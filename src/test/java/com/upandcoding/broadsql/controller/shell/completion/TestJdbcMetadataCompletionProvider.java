package com.upandcoding.broadsql.controller.shell.completion;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidate;
import com.upandcoding.broadsql.controller.shell.reader.CompletionCandidateType;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * SPRINT 0917-02, section 41 ("Metadata tests") - {@link JdbcMetadataCompletionProvider} exercised
 * against a real, throwaway in-memory H2 database (same fixture policy as every other DB-backed test
 * in this repo - see {@code TestDatabaseConnections}), not a mock of {@code DatabaseMetaData}: the
 * point of this provider is to be JDBC-metadata-driven, so its tests should be too.
 */
class TestJdbcMetadataCompletionProvider {

	private DatabaseConnection db;

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	private JdbcMetadataCompletionProvider providerWithSchema() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory(
				"CREATE TABLE CUSTOMER (CUSTOMER_ID INT, CUSTOMER_NAME VARCHAR(50), STATUS VARCHAR(10))",
				"CREATE TABLE CUSTOMER_ADDRESS (ID INT, CUSTOMER_ID INT, ADDRESS VARCHAR(100))",
				"CREATE TABLE PRODUCT (PRODUCT_ID INT, NAME VARCHAR(50))",
				"CREATE TABLE ORDERS (ORDER_ID INT, CUSTOMER_ID INT, TOTAL DECIMAL(10,2))");
		return new JdbcMetadataCompletionProvider(db, new JdbcMetadataCompletionCache());
	}

	private List<CompletionCandidate> complete(JdbcMetadataCompletionProvider provider, String textWithCursor) {
		int cursor = textWithCursor.indexOf('|');
		String text = textWithCursor.substring(0, cursor) + textWithCursor.substring(cursor + 1);
		CompletionContext ctx = SqlCompletionLexer.analyze(text, cursor, true);
		return provider.complete(ctx);
	}

	private List<String> values(JdbcMetadataCompletionProvider provider, String textWithCursor) {
		return complete(provider, textWithCursor).stream().map(CompletionCandidate::getValue).toList();
	}

	@Test
	void uniqueTableCompletesDirectly() throws BroadSQLException {
		Assertions.assertEquals(List.of("PRODUCT"), values(providerWithSchema(), "SELECT * FROM prod|"));
	}

	@Test
	void ambiguousTablePrefixExposesBothCandidatesWithoutChoosingArbitrarily() throws BroadSQLException {
		List<String> result = values(providerWithSchema(), "SELECT * FROM cust|");
		Assertions.assertTrue(result.contains("CUSTOMER"), result.toString());
		Assertions.assertTrue(result.contains("CUSTOMER_ADDRESS"), result.toString());
	}

	@Test
	void tableCandidatesAreTypedTableOrView() throws BroadSQLException {
		List<CompletionCandidate> result = complete(providerWithSchema(), "SELECT * FROM prod|");
		Assertions.assertEquals(CompletionCandidateType.TABLE, result.get(0).getType());
	}

	@Test
	void insertIntoDeleteFromUpdateAllOfferTables() throws BroadSQLException {
		JdbcMetadataCompletionProvider provider = providerWithSchema();
		Assertions.assertTrue(values(provider, "INSERT INTO prod|").contains("PRODUCT"));
		Assertions.assertTrue(values(provider, "DELETE FROM prod|").contains("PRODUCT"));
		Assertions.assertTrue(values(provider, "UPDATE prod| SET").contains("PRODUCT"));
	}

	@Test
	void unqualifiedColumnCompletionWhenExactlyOneTableIsReferenced() throws BroadSQLException {
		List<String> result = values(providerWithSchema(), "SELECT customer_na| FROM CUSTOMER");
		Assertions.assertEquals(List.of("CUSTOMER_NAME"), result);
	}

	@Test
	void unqualifiedColumnCompletionIsSkippedWhenTableIsAmbiguous() throws BroadSQLException {
		// Neither CUSTOMER nor ORDERS can be assumed - both are referenced.
		List<String> result = values(providerWithSchema(), "SELECT | FROM CUSTOMER c JOIN ORDERS o ON o.CUSTOMER_ID = c.CUSTOMER_ID");
		Assertions.assertTrue(result.isEmpty(), result.toString());
	}

	@Test
	void aliasDotColumnResolvesToTheAliasedTablesColumns() throws BroadSQLException {
		List<String> result = values(providerWithSchema(), "SELECT c.customer_na| FROM customer c");
		Assertions.assertEquals(List.of("CUSTOMER_NAME"), result);
	}

	@Test
	void aliasDotColumnWorksWithExplicitAsKeyword() throws BroadSQLException {
		List<String> result = values(providerWithSchema(), "SELECT c.customer_na| FROM customer AS c");
		Assertions.assertEquals(List.of("CUSTOMER_NAME"), result);
	}

	@Test
	void aliasResolutionWorksInWhereClauseEvenWhenTableIsTypedAfterTheCursor() throws BroadSQLException {
		List<String> result = values(providerWithSchema(), "SELECT * FROM customer c WHERE c.| ");
		Assertions.assertTrue(result.contains("CUSTOMER_ID"), result.toString());
		Assertions.assertTrue(result.contains("CUSTOMER_NAME"), result.toString());
		Assertions.assertTrue(result.contains("STATUS"), result.toString());
	}

	@Test
	void aliasResolutionWorksAfterAJoinOnClause() throws BroadSQLException {
		List<String> result = values(providerWithSchema(),
				"SELECT * FROM customer c JOIN orders o ON o.|");
		Assertions.assertTrue(result.contains("ORDER_ID"), result.toString());
		Assertions.assertTrue(result.contains("CUSTOMER_ID"), result.toString());
		Assertions.assertTrue(result.contains("TOTAL"), result.toString());
	}

	@Test
	void columnDotDoesNotMatchAsASchemaQualifiedTable() throws BroadSQLException {
		// "c" resolves as an alias, not a schema - so completion after "c." must be columns, not tables.
		List<CompletionCandidate> result = complete(providerWithSchema(), "SELECT * FROM customer c WHERE c.|");
		Assertions.assertFalse(result.isEmpty());
		Assertions.assertEquals(CompletionCandidateType.COLUMN, result.get(0).getType());
	}

	@Test
	void schemaQualifiedTableCompletion() throws BroadSQLException {
		// H2's default, unquoted-identifier schema is PUBLIC.
		List<String> result = values(providerWithSchema(), "SELECT * FROM public.prod|");
		Assertions.assertEquals(List.of("PRODUCT"), result);
	}

	@Test
	void noCandidatesWithoutAConnection() throws BroadSQLException {
		JdbcMetadataCompletionProvider provider = providerWithSchema();
		CompletionContext disconnected = SqlCompletionLexer.analyze("SELECT * FROM prod", "SELECT * FROM prod".length(), false);
		Assertions.assertTrue(provider.complete(disconnected).isEmpty());
	}

	@Test
	void nullConnectionNeverThrows() {
		JdbcMetadataCompletionProvider provider = new JdbcMetadataCompletionProvider(null, new JdbcMetadataCompletionCache());
		CompletionContext ctx = SqlCompletionLexer.analyze("SELECT * FROM prod", "SELECT * FROM prod".length(), true);
		Assertions.assertTrue(provider.complete(ctx).isEmpty());
	}

	@Test
	void repeatedCompletionUsesTheCache() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE PRODUCT (PRODUCT_ID INT)");
		JdbcMetadataCompletionCache cache = new JdbcMetadataCompletionCache();
		JdbcMetadataCompletionProvider provider = new JdbcMetadataCompletionProvider(db, cache);
		values(provider, "SELECT * FROM prod|");

		// A table created after the first (now cached) lookup must not appear until the cache is
		// explicitly invalidated (section 25/26) - proves the second call actually reused the cache
		// instead of re-querying DatabaseMetaData.
		db.executeUpdateQuery("CREATE TABLE PRODUCT_CATEGORY (ID INT)");
		Assertions.assertFalse(values(provider, "SELECT * FROM prod|").contains("PRODUCT_CATEGORY"));

		cache.invalidate(db.getPlatform().getId());
		Assertions.assertTrue(values(provider, "SELECT * FROM prod|").contains("PRODUCT_CATEGORY"));
	}

	@Test
	void connectionCacheIsolationBetweenTwoDifferentPlatforms() throws BroadSQLException {
		DatabaseConnection dbA = TestDatabaseConnections.connectInMemory("CREATE TABLE ALPHA_ONLY (ID INT)");
		DatabaseConnection dbB = TestDatabaseConnections.connectInMemory("CREATE TABLE BETA_ONLY (ID INT)");
		try {
			JdbcMetadataCompletionCache sharedCache = new JdbcMetadataCompletionCache();
			JdbcMetadataCompletionProvider providerA = new JdbcMetadataCompletionProvider(dbA, sharedCache);
			JdbcMetadataCompletionProvider providerB = new JdbcMetadataCompletionProvider(dbB, sharedCache);

			Assertions.assertTrue(values(providerA, "SELECT * FROM |").stream().anyMatch(v -> v.equals("ALPHA_ONLY")));
			Assertions.assertFalse(values(providerA, "SELECT * FROM |").stream().anyMatch(v -> v.equals("BETA_ONLY")));
			Assertions.assertTrue(values(providerB, "SELECT * FROM |").stream().anyMatch(v -> v.equals("BETA_ONLY")));
			Assertions.assertFalse(values(providerB, "SELECT * FROM |").stream().anyMatch(v -> v.equals("ALPHA_ONLY")));
		} finally {
			TestDatabaseConnections.close(dbA);
			TestDatabaseConnections.close(dbB);
		}
	}
}
