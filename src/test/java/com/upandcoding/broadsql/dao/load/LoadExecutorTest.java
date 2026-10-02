package com.upandcoding.broadsql.dao.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.errors.SqlExecutionException;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

class LoadExecutorTest {

	private DatabaseConnection db;

	@BeforeEach
	void setUp() throws BroadSQLException {
		db = TestDatabaseConnections.connectInMemory("CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(200))");
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	private LoadPlan planFor(Path csv) throws BroadSQLException {
		LoadTarget target = LoadTargetResolver.resolve(db, "CUSTOMER");
		LoadSourceReader source = LoadSourceReader.read(csv.toString(), ';');
		return LoadValidator.validate(target, csv.toString(), source);
	}

	@Test
	void hostileLookingValuesAreStoredAsPlainDataNeverExecuted(@TempDir Path dir) throws BroadSQLException, IOException, SQLException {
		Path csv = dir.resolve("customer.csv");
		String hostileName = "Robert'); DROP TABLE CUSTOMER; --";
		Files.writeString(csv, "ID;NAME\n1;\"" + hostileName + "\"\n");

		LoadResult result = LoadExecutor.execute(db, planFor(csv));

		Assertions.assertTrue(result.isCommitted());
		Assertions.assertEquals(1, result.getRowsInserted());

		try (Statement statement = db.getDirectConnection().createStatement();
				ResultSet rs = statement.executeQuery("SELECT NAME FROM CUSTOMER WHERE ID = 1")) {
			Assertions.assertTrue(rs.next());
			Assertions.assertEquals(hostileName, rs.getString("NAME"));
		}

		try (Statement statement = db.getDirectConnection().createStatement();
				ResultSet rs = statement.executeQuery("SELECT COUNT(*) AS N FROM CUSTOMER")) {
			rs.next();
			Assertions.assertEquals(1, rs.getInt("N"), "CUSTOMER table must still exist and contain exactly the inserted row");
		}
	}

	@Test
	void insertsAllValidRowsAndCommitsOnce(@TempDir Path dir) throws BroadSQLException, IOException, SQLException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME\n1;Alice\n2;Bob\n3;Carol\n");

		LoadResult result = LoadExecutor.execute(db, planFor(csv));

		Assertions.assertEquals(3, result.getRowsInserted());
		try (Statement statement = db.getDirectConnection().createStatement();
				ResultSet rs = statement.executeQuery("SELECT COUNT(*) AS N FROM CUSTOMER")) {
			rs.next();
			Assertions.assertEquals(3, rs.getInt("N"));
		}
	}

	@Test
	void aDuplicateKeyFailureRollsBackTheWholeLoad(@TempDir Path dir) throws BroadSQLException, IOException, SQLException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME\n1;Alice\n2;Bob\n1;DuplicateId\n");

		Assertions.assertThrows(SqlExecutionException.class, () -> LoadExecutor.execute(db, planFor(csv)));

		try (Statement statement = db.getDirectConnection().createStatement();
				ResultSet rs = statement.executeQuery("SELECT COUNT(*) AS N FROM CUSTOMER")) {
			rs.next();
			Assertions.assertEquals(0, rs.getInt("N"), "no row should have been committed after the batch failed");
		}
	}

	@Test
	void restoresThePriorAutoCommitStateAfterSuccess(@TempDir Path dir) throws BroadSQLException, IOException, SQLException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME\n1;Alice\n");
		db.getDirectConnection().setAutoCommit(true);

		LoadExecutor.execute(db, planFor(csv));

		Assertions.assertTrue(db.getDirectConnection().getAutoCommit());
	}

	@Test
	void refusesToExecuteAPlanThatIsNotReady(@TempDir Path dir) throws BroadSQLException, IOException {
		Path csv = dir.resolve("customer.csv");
		Files.writeString(csv, "ID;NAME;BOGUS\n1;Alice;x\n");

		LoadPlan plan = planFor(csv);
		Assertions.assertFalse(plan.isReadyToExecute());
		Assertions.assertThrows(BroadSQLException.class, () -> LoadExecutor.execute(db, plan));
	}
}
