package com.upandcoding.broadsql.dao.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class LoadValidatorTest {

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
	void aFullyValidFileProducesAReadyToExecutePlan(@TempDir Path dir) throws BroadSQLException, IOException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME;START_DATE\n1;Alice;2026-01-15\n2;Bob;2026-02-20\n");

		LoadTarget target = LoadTargetResolver.resolve(db, "CUSTOMER");
		LoadSourceReader source = LoadSourceReader.read(csv.toString(), ';');
		LoadPlan plan = LoadValidator.validate(target, csv.toString(), source);

		Assertions.assertTrue(plan.isReadyToExecute());
		Assertions.assertEquals(2, plan.getSourceRowCount());
		Assertions.assertEquals(2, plan.getValidRowCount());
		Assertions.assertEquals(0, plan.getRejectedRowCount());
		Assertions.assertEquals(2, plan.getBoundRows().size());
	}

	@Test
	void reorderedSourceColumnsMapByNameNotPosition(@TempDir Path dir) throws BroadSQLException, IOException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "NAME;ID\nAlice;1\n");

		LoadTarget target = LoadTargetResolver.resolve(db, "CUSTOMER");
		LoadPlan plan = LoadValidator.validate(target, csv.toString(), LoadSourceReader.read(csv.toString(), ';'));

		Assertions.assertTrue(plan.isReadyToExecute());
		Object[] row = plan.getBoundRows().get(0);
		Assertions.assertEquals("NAME", plan.getInsertColumns().get(0));
		Assertions.assertEquals("Alice", row[0]);
		Assertions.assertEquals("ID", plan.getInsertColumns().get(1));
		Assertions.assertEquals(1, row[1]);
	}

	@Test
	void aSubsetOfColumnsOmitsTheRestFromTheInsertColumnList(@TempDir Path dir) throws BroadSQLException, IOException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME\n1;Alice\n");

		LoadTarget target = LoadTargetResolver.resolve(db, "CUSTOMER");
		LoadPlan plan = LoadValidator.validate(target, csv.toString(), LoadSourceReader.read(csv.toString(), ';'));

		Assertions.assertTrue(plan.isReadyToExecute());
		Assertions.assertEquals(2, plan.getInsertColumns().size());
		Assertions.assertFalse(plan.getInsertColumns().contains("START_DATE"));
	}

	@Test
	void anUnknownSourceHeaderRefusesTheWholeLoad(@TempDir Path dir) throws BroadSQLException, IOException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME;BOGUS_COLUMN\n1;Alice;x\n");

		LoadTarget target = LoadTargetResolver.resolve(db, "CUSTOMER");
		LoadPlan plan = LoadValidator.validate(target, csv.toString(), LoadSourceReader.read(csv.toString(), ';'));

		Assertions.assertFalse(plan.isReadyToExecute());
		Assertions.assertTrue(plan.getUnmappedHeaders().contains("BOGUS_COLUMN"));
		Assertions.assertEquals(0, plan.getBoundRows().size());
	}

	@Test
	void aHostileLookingHeaderIsRejectedAsUnknownNeverUsedAsAnIdentifier(@TempDir Path dir) throws BroadSQLException, IOException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME);DROP TABLE CUSTOMER;--\n1;Alice\n");

		LoadTarget target = LoadTargetResolver.resolve(db, "CUSTOMER");
		LoadPlan plan = LoadValidator.validate(target, csv.toString(), LoadSourceReader.read(csv.toString(), ';'));

		Assertions.assertFalse(plan.isReadyToExecute());
		Assertions.assertFalse(plan.getUnmappedHeaders().isEmpty());
	}

	@Test
	void oneBadValueRejectsTheWholeLoadNotJustThatRow(@TempDir Path dir) throws BroadSQLException, IOException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME;START_DATE\n1;Alice;2026-01-15\n2;Bob;NOT-A-DATE\n");

		LoadTarget target = LoadTargetResolver.resolve(db, "CUSTOMER");
		LoadPlan plan = LoadValidator.validate(target, csv.toString(), LoadSourceReader.read(csv.toString(), ';'));

		Assertions.assertFalse(plan.isReadyToExecute());
		Assertions.assertEquals(2, plan.getSourceRowCount());
		Assertions.assertEquals(1, plan.getIssues().size());
		Assertions.assertEquals(2, plan.getIssues().get(0).getSourceRow());
		// The valid row (1) is still converted and held in boundRows - it's plan.isReadyToExecute()
		// that gates whether LoadExecutor may run at all, not the presence of any individually-valid rows.
		Assertions.assertEquals(1, plan.getBoundRows().size());
	}

	@Test
	void duplicateHeadersAreRejectedBeforeAnyRowIsRead(@TempDir Path dir) throws IOException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;ID;NAME\n1;2;Alice\n");

		Assertions.assertThrows(BroadSQLException.class, () -> LoadSourceReader.read(csv.toString(), ';'));
	}

	@Test
	void caseInsensitiveHeaderMatchingWorksForDerbyStyleUppercaseColumns(@TempDir Path dir) throws BroadSQLException, IOException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "id;name\n1;Alice\n");

		LoadTarget target = LoadTargetResolver.resolve(db, "CUSTOMER");
		LoadPlan plan = LoadValidator.validate(target, csv.toString(), LoadSourceReader.read(csv.toString(), ';'));

		Assertions.assertTrue(plan.isReadyToExecute());
	}
}
