package com.upandcoding.broadsql.controller.shell.commands;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandFindForeignKeys;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandFindIndexes;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowForeignKeys;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowIndexes;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowReferences;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * SPRINT 2409K: the SHOW/FIND metadata commands end to end (real H2, real console output): what they
 * print, and how an empty result differs from a missing table.
 */
class TestMetadataCommands {

	private DatabaseConnection db;
	private CapturingShellConsole console;
	private ConsoleSettings settings;

	@BeforeEach
	void setUp() throws BroadSQLException {
		settings = TestDatabaseConnections.defaultConsoleSettings();
		db = TestDatabaseConnections.connectInMemory(settings,
				"CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(80) NOT NULL, BALANCE DECIMAL(10,2) DEFAULT 0)",
				"CREATE TABLE ORDERS (ID INT PRIMARY KEY, CUSTOMER_ID INT NOT NULL, CONSTRAINT FK_ORDER_CUST FOREIGN KEY (CUSTOMER_ID) REFERENCES CUSTOMER(ID))",
				"CREATE INDEX IX_ORDERS_CUST ON ORDERS(CUSTOMER_ID, ID)", "CREATE TABLE NOTES (TXT VARCHAR(10))");
		console = new CapturingShellConsole();
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
	}

	/** The grid with cell padding removed: {@code |ID     |INTEGER |} becomes {@code |ID|INTEGER|}. */
	private static String cells(String out) {
		return out.replaceAll(" +\\|", "|");
	}

	private String run(Class<? extends Command> type, String query) throws BroadSQLException {
		Command cmd = CommandTestSupport.create(type, db, console, settings);
		cmd.execute(query);
		return console.getOutput();
	}

	@Test
	void showFkAndReferencesPrintBothDirections() throws Exception {
		String fk = run(CommandShowForeignKeys.class, "SHOW FK ORDERS");
		Assertions.assertTrue(fk.contains("FK_ORDER_CUST") && fk.contains("REFERENCES_TABLE") && fk.contains("CUSTOMER"), fk);
		console = new CapturingShellConsole();
		String refs = run(CommandShowReferences.class, "SHOW REFERENCES CUSTOMER");
		Assertions.assertTrue(refs.contains("REFERENCING_TABLE") && refs.contains("ORDERS") && refs.contains("FK_ORDER_CUST"), refs);
	}

	@Test
	void anEmptyResultIsReportedAsSuchNotAsAnEmptyGrid() throws Exception {
		String out = run(CommandShowForeignKeys.class, "SHOW FK NOTES");
		Assertions.assertTrue(out.contains("declares no foreign keys"), out);
		Assertions.assertFalse(out.contains("rows fetched"), out);
		Assertions.assertFalse(console.wasErrorReported(), out);
	}

	@Test
	void aMissingTableIsAnErrorNotAnEmptyResult() throws Exception {
		String out = run(CommandShowIndexes.class, "SHOW INDEXES NO_SUCH_TABLE");
		Assertions.assertTrue(out.contains("ERROR") && out.contains("does not exist"), out);
		Assertions.assertTrue(console.wasErrorReported());
	}

	@Test
	void showIndexesListsCompositeIndexesInOrder() throws Exception {
		String out = run(CommandShowIndexes.class, "SHOW INDEXES ORDERS");
		String grid = cells(out);
		int first = grid.indexOf("|IX_ORDERS_CUST|1|CUSTOMER_ID|NON-UNIQUE|");
		int second = grid.indexOf("|IX_ORDERS_CUST|2|ID|NON-UNIQUE|");
		Assertions.assertTrue(first >= 0 && second > first, out);
		Assertions.assertTrue(grid.contains("|1|ID|UNIQUE|"), "the primary key index is a real unique index:\n" + out);
	}

	@Test
	void findFkAndItsReferenceAliasShareOneCommand() throws Exception {
		Assertions.assertTrue(java.util.List.of(new CommandFindForeignKeys().getKeywords()).containsAll(java.util.List.of("FIND FK", "FIND REFERENCE")));
		String out = run(CommandFindForeignKeys.class, "FIND REFERENCE cust");
		Assertions.assertTrue(out.contains("FK_ORDER_CUST") && out.contains("REFERENCES_COLUMN"), out);
	}

	@Test
	void findIndexByTableName() throws Exception {
		String out = run(CommandFindIndexes.class, "FIND INDEX orders");
		Assertions.assertTrue(out.contains("IX_ORDERS_CUST") && out.contains("TABLE"), out);
	}

}
