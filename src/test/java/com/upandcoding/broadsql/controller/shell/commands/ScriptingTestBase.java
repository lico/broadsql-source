package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.controller.shell.scripts.RunIds;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptContextStack;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptRunResult;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptValue;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.LastCopyableResultHolder;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.broadsql.dao.api.ApiSessionVariablesHolder;

/**
 * SPRINT 0110A: the in-process harness of the scripting tests: a real interpreter with every command registered
 * ({@link CommandTestSupport#createFullCommandInterpreter}), a real in-memory H2 database, a Scripts Library folder.
 * Statements are typed through the interpreter's own line execution ({@code executeMultiStatementLine}), so they go
 * through the real path: interpreter, command or {@code CommandDefault}, {@code DatabaseConnection}.
 */
abstract class ScriptingTestBase {

	@TempDir
	Path tmp;

	protected Path library;
	protected ConsoleSettings settings;
	protected DatabaseConnection db;
	protected CapturingShellConsole console;
	protected CommandInterpreter interpreter;

	@BeforeEach
	void setUpScripting() throws Exception {
		library = Files.createDirectories(tmp.resolve("scripts"));
		settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(library.toString());
		settings.setExtractFolderName(Files.createDirectories(tmp.resolve("out")).toString() + java.io.File.separator);
		settings.setCsvSeparatorRaw("SEMICOLON");
		db = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE CUSTOMER (ID INT PRIMARY KEY, NAME VARCHAR(100), COUNTRY VARCHAR(10))");
		console = new CapturingShellConsole();
		interpreter = CommandTestSupport.createFullCommandInterpreter(settings, console, db);
		db.setCmdLineConsole(console);
		ApiSessionVariablesHolder.clearAll();
		LastQueryResultHolder.set(null);
	}

	@AfterEach
	void tearDownScripting() throws BroadSQLException {
		TestDatabaseConnections.close(db);
		LastQueryResultHolder.set(null);
		ApiSessionVariablesHolder.clearAll();
		CommandCancellation.resetRun();
		RunIds.setGeneratorForTests(null);
	}

	// ---- input ----

	/** Types {@code text} at the prompt (one or several {@code ;}-separated statements). */
	protected void line(String text) {
		interpreter.executeMultiStatementLine(text);
	}

	protected Path lib(String relative, String text) throws IOException {
		Path file = library.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

	/** Types {@code callText} (an {@code @}, {@code LIB RUN}, {@code DUMP} statement) and returns the result of the run it started. */
	protected ScriptRunResult run(String callText) {
		line(callText);
		return lastRun();
	}

	protected ScriptRunResult lastRun() {
		List<ScriptRunResult> runs = interpreter.getScriptContext().currentStatement().getCalledRuns();
		Assertions.assertFalse(runs.isEmpty(), "no Script run was recorded: " + output());
		return runs.get(runs.size() - 1);
	}

	/** Whether the last typed statement failed, as the typed line saw it. */
	protected Boolean lastStatementStatedFailed() {
		return interpreter.getScriptContext().currentStatement().getExplicitFailed();
	}

	// ---- state ----

	protected ScriptValue var(String name) {
		return interpreter.getScriptVariables().get(name);
	}

	protected Object value(String name) {
		ScriptValue v = var(name);
		Assertions.assertNotNull(v, name + " is not defined: " + output());
		return v.getValue();
	}

	protected String output() {
		return console.getOutput();
	}

	protected void clearOutput() {
		console.clear();
	}

	/** Every non-blank output line without the prompt prefix. */
	protected List<String> outputLines() {
		List<String> lines = new ArrayList<>();
		for (String l : output().split("\n")) {
			String t = l.replace("\r", "");
			if (t.startsWith(console.getPrompt())) {
				t = t.substring(console.getPrompt().length());
			}
			if (!t.isBlank()) {
				lines.add(t);
			}
		}
		return lines;
	}

	/** A value read on the session connection (sees its uncommitted work). */
	protected Object sql(String query) throws SQLException {
		try (Statement statement = db.getDirectConnection().createStatement(); ResultSet rs = statement.executeQuery(query)) {
			return rs.next() ? rs.getObject(1) : null;
		}
	}

	protected long count(String table) throws SQLException {
		return ((Number) sql("SELECT COUNT(*) FROM " + table)).longValue();
	}

	/** A value read on a second, independent connection: only committed work is visible. */
	protected Object committed(String query) throws SQLException {
		try (Connection other = DriverManager.getConnection(db.getPlatform().getUrl()); Statement statement = other.createStatement();
				ResultSet rs = statement.executeQuery(query)) {
			return rs.next() ? rs.getObject(1) : null;
		}
	}

	protected void autocommit(boolean on) throws BroadSQLException {
		db.setAutoCommit(on);
		db.setHasUncommitted(false);
	}

	protected static int occurrences(String text, String fragment) {
		int count = 0;
		int i = text.indexOf(fragment);
		while (i >= 0) {
			count++;
			i = text.indexOf(fragment, i + fragment.length());
		}
		return count;
	}

	protected ScriptContextStack context() {
		return interpreter.getScriptContext();
	}

	private Path logFolder;

	/** Turns the activity log on (the {@code IsLogActivated} setting), written under a temporary folder. */
	protected void enableActivityLog() throws IOException {
		logFolder = Files.createDirectories(tmp.resolve("logs"));
		settings.setLogDefaultActivated(true);
		settings.setLogFolderName(logFolder.toString());
		com.upandcoding.broadsql.controller.shell.output.ConsoleLogger logger = new com.upandcoding.broadsql.controller.shell.output.ConsoleLogger();
		logger.consoleSettings = settings;
		logger.database = db;
		logger.console = console;
		console.consoleLogger = logger;
	}

	/** Everything written to the activity log so far. */
	protected String activityLog() throws IOException {
		StringBuilder text = new StringBuilder();
		try (var files = Files.list(logFolder)) {
			for (Path file : files.sorted().toList()) {
				text.append(Files.readString(file, StandardCharsets.UTF_8));
			}
		}
		return text.toString();
	}
}
