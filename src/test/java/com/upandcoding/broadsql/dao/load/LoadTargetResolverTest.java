package com.upandcoding.broadsql.dao.load;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class LoadTargetResolverTest {

	private DatabaseConnection db;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(50), START_DATE DATE)");
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	@Test
	void resolvesAnExistingTableWithItsRealColumnNamesAndTypes() throws BroadSQLException {
		LoadTarget target = LoadTargetResolver.resolve(db, "CUSTOMER");

		Assertions.assertEquals("CUSTOMER", target.getTableName());
		Assertions.assertEquals(3, target.getColumns().size());
		Assertions.assertNotNull(target.findColumn("ID"));
		Assertions.assertNotNull(target.findColumn("name"));
	}

	@Test
	void rejectsATableThatDoesNotExist() {
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> LoadTargetResolver.resolve(db, "DOESNOTEXIST"));
		Assertions.assertTrue(ex.getMessage().contains("does not exist"));
	}

	@Test
	void aHostileLookingTableNameNeverReachesSqlText() {
		String hostile = "CUSTOMER; DROP TABLE CUSTOMER; --";
		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> LoadTargetResolver.resolve(db, hostile));
		Assertions.assertTrue(ex.getMessage().contains("does not exist"));
	}

	@Test
	void aViewIsNotALoadTarget() throws BroadSQLException {
		db.executeUpdateQuery("CREATE VIEW CUSTOMER_VIEW AS SELECT * FROM CUSTOMER");

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> LoadTargetResolver.resolve(db, "CUSTOMER_VIEW"));

		Assertions.assertTrue(ex.getMessage().contains("is a view, not a table"), ex.getMessage());
	}
}
