package com.upandcoding.broadsql.controller.shell.commands.core.scripting;

import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

class TestJsScriptRunner {

	@Test
	void evalsArithmeticAndPrintsThroughTheConsole() throws Exception {
		CapturingShellConsole console = new CapturingShellConsole();

		JsScriptRunner.run("println(2 + 2);", console, null, null, null);

		Assertions.assertTrue(console.getOutput().contains("4"));
	}

	@Test
	void argsAreBoundAndIndexable() throws Exception {
		CapturingShellConsole console = new CapturingShellConsole();

		JsScriptRunner.run("println(args[0] + '-' + args[1]);", console, null, null, new String[] { "hello", "world" });

		Assertions.assertTrue(console.getOutput().contains("hello-world"));
	}

	@Test
	void dbAliasesTheActiveConnectionWithoutClosingItOnScriptSideClose() throws Exception {
		CapturingShellConsole console = new CapturingShellConsole();
		DatabaseConnection active = TestDatabaseConnections.connectInMemory("CREATE TABLE T (ID INT)", "INSERT INTO T VALUES (7)");

		JsScriptRunner.run("var rows = db.execute('SELECT ID FROM T'); println(rows.get(0)['ID']); db.close();",
				console, active, null, null);

		Assertions.assertTrue(console.getOutput().contains("7"));
		Assertions.assertFalse(active.getDirectConnection().isClosed());
	}

	@Test
	void connectOpensTwoIndependentConnectionsInOneScript() throws Exception {
		CapturingShellConsole console = new CapturingShellConsole();
		String sourceId = "RS_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		String targetId = "RT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

		DatabaseDefinitionsVault vault = TestDatabaseConnections.newFileBackedVault();
		for (String id : new String[] { sourceId, targetId }) {
			DatabaseDefinition definition = new DatabaseDefinition(id);
			definition.setDbType("H2");
			definition.setUrl("jdbc:h2:mem:" + id + ";DB_CLOSE_DELAY=-1");
			definition.setStatus(DatabaseDefinition.STATUS_ACTIVE);
			definition.setEnvironment("LOCAL");
			vault.saveDatabaseDefinition(definition);
		}
		vault.load();

		String script = "var source = connect('" + sourceId + "');\n"
				+ "var target = connect('" + targetId + "');\n"
				+ "source.executeUpdate('CREATE TABLE ORDERS (ID INT, AMOUNT DECIMAL(10,2))');\n"
				+ "source.executeUpdate(\"INSERT INTO ORDERS VALUES (1, 100.00)\");\n"
				+ "target.executeUpdate('CREATE TABLE ORDERS_ARCHIVE (ID INT, AMOUNT DECIMAL(10,2))');\n"
				+ "var rows = source.execute('SELECT ID, AMOUNT FROM ORDERS');\n"
				+ "var migrated = 0;\n"
				+ "for (var i = 0; i < rows.size(); i++) {\n"
				+ "    var row = rows.get(i);\n"
				+ "    target.executeUpdate('INSERT INTO ORDERS_ARCHIVE VALUES (' + row['ID'] + ', ' + row['AMOUNT'] + ')');\n"
				+ "    migrated++;\n"
				+ "}\n"
				+ "println('Migrated ' + migrated);\n"
				+ "source.close();\n"
				+ "target.close();\n";

		JsScriptRunner.run(script, console, null, vault, null);

		Assertions.assertTrue(console.getOutput().contains("Migrated 1"));
	}

	@Test
	void aThrownScriptValueBecomesABroadSQLExceptionWithALineNumberAndDoesNotCrash() {
		CapturingShellConsole console = new CapturingShellConsole();

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class,
				() -> JsScriptRunner.run("var x = 1;\nthrow 'boom';\n", console, null, null, null));

		Assertions.assertTrue(ex.getMessage().contains("line 2"), "expected line number in: " + ex.getMessage());
		Assertions.assertTrue(ex.getMessage().contains("boom"));
	}

	@Test
	void aSyntaxErrorBecomesABroadSQLExceptionInsteadOfCrashing() {
		CapturingShellConsole console = new CapturingShellConsole();

		Assertions.assertThrows(BroadSQLException.class,
				() -> JsScriptRunner.run("var y = ;", console, null, null, null));
	}

	@Test
	void aSqlErrorFromExecuteBecomesABroadSQLExceptionPreservingTheOriginalMessage() {
		CapturingShellConsole console = new CapturingShellConsole();

		BroadSQLException ex = Assertions.assertThrows(BroadSQLException.class, () -> JsScriptRunner.run(
				"var conn = connect('DOES_NOT_EXIST'); conn.execute('SELECT 1');",
				console, null, TestDatabaseConnections.newFileBackedVault(), null));

		Assertions.assertTrue(ex.getMessage().contains("DOES_NOT_EXIST"), "expected the original message preserved: " + ex.getMessage());
	}
}
