package com.upandcoding.broadsql.controller.shell.commands;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibRun;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandExternalFile;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;

/**
 * SPRINT 2409K: accented and non-Western characters survive the whole Script path - file bytes,
 * {@code ScriptTextIO}, {@code LIB RUN}/{@code @}, the interpreter, and the SQL string JDBC receives -
 * checked by reading the value back from a real H2 table with a bound parameter. The windows-1252 case
 * failed before the fix under every JDK 21 default charset (IBM850 with the launchers'
 * {@code -Dfile.encoding=Cp850}, UTF-8 otherwise), because the non-UTF-8 fallback was the platform default.
 */
class TestScriptUnicodeExecution {

	private static final String WESTERN = "Clôture é è à ù ç €";
	/** Japanese and Greek: only representable in a Unicode file. */
	private static final String NON_WESTERN = "日本語 Ελληνικά";

	@TempDir
	Path tmp;

	private Path library;
	private ConsoleSettings settings;
	private DatabaseConnection db;
	private CapturingShellConsole console;
	private CommandInterpreter interpreter;

	@BeforeEach
	void setUp() throws BroadSQLException, IOException {
		library = Files.createDirectories(tmp.resolve("scripts"));
		settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setScriptsLibraryPath(library.toString());
		db = TestDatabaseConnections.connectInMemory(settings, "CREATE TABLE LABELS (ID INT PRIMARY KEY, DESCR VARCHAR(100))");
		console = new CapturingShellConsole();
		interpreter = CommandTestSupport.createFullCommandInterpreter(settings, console, db);
	}

	@AfterEach
	void tearDown() throws BroadSQLException {
		TestDatabaseConnections.close(db);
		LastQueryResultHolder.set(null);
	}

	private void write(String name, String text, Charset charset, boolean bom) throws IOException {
		byte[] body = text.getBytes(charset);
		byte[] all = body;
		if (bom) {
			all = new byte[body.length + 3];
			all[0] = (byte) 0xEF;
			all[1] = (byte) 0xBB;
			all[2] = (byte) 0xBF;
			System.arraycopy(body, 0, all, 3, body.length);
		}
		Files.write(library.resolve(name), all);
	}

	private void libRun(String name) throws BroadSQLException {
		CommandLibRun cmd = CommandTestSupport.create(CommandLibRun.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(interpreter);
		cmd.execute("LIB RUN " + name);
	}

	private void at(String name) throws BroadSQLException {
		CommandExternalFile cmd = CommandTestSupport.create(CommandExternalFile.class, db, console, settings);
		cmd.setConsoleCommandInterpreter(interpreter);
		cmd.execute("@" + name);
	}

	private String stored(int id) throws SQLException {
		try (PreparedStatement ps = db.getDirectConnection().prepareStatement("SELECT DESCR FROM LABELS WHERE ID = ?")) {
			ps.setInt(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	private int countWhereDescrIs(String value) throws SQLException {
		try (PreparedStatement ps = db.getDirectConnection().prepareStatement("SELECT COUNT(*) FROM LABELS WHERE DESCR = ?")) {
			ps.setString(1, value);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	@Test
	void utf8ScriptReachesJdbcIntact() throws Exception {
		write("utf8.sql", "INSERT INTO LABELS VALUES (1, '" + WESTERN + "');\nINSERT INTO LABELS VALUES (2, '" + NON_WESTERN + "');\n",
				StandardCharsets.UTF_8, false);
		libRun("utf8.sql");
		Assertions.assertEquals(WESTERN, stored(1), console.getOutput());
		Assertions.assertEquals(NON_WESTERN, stored(2), console.getOutput());
	}

	@Test
	void utf8WithBomScriptReachesJdbcIntact() throws Exception {
		write("bom.sql", "INSERT INTO LABELS VALUES (1, '" + NON_WESTERN + "');\n", StandardCharsets.UTF_8, true);
		at("bom.sql");
		Assertions.assertEquals(NON_WESTERN, stored(1), console.getOutput());
	}

	@Test
	void windows1252ScriptIsDecodedExplicitlyNotWithThePlatformDefault() throws Exception {
		write("ansi.sql", "INSERT INTO LABELS VALUES (1, '" + WESTERN + "');\n", Charset.forName("windows-1252"), false);
		libRun("ansi.sql");
		Assertions.assertEquals(WESTERN, stored(1), console.getOutput());
	}

	@Test
	void anAccentedWhereClauseMatchesTheStoredRow() throws Exception {
		db.executeUpdateQuery("INSERT INTO LABELS VALUES (7, 'Clôture')");
		write("where.sql", "UPDATE LABELS SET ID = 8 WHERE DESCR = 'Clôture';\n", StandardCharsets.UTF_8, false);
		libRun("where.sql");
		Assertions.assertEquals("Clôture", stored(8), console.getOutput());
		Assertions.assertEquals(1, countWhereDescrIs("Clôture"));
	}

	@Test
	void aUtf8ListSourceFileKeepsItsAccents() throws Exception {
		db.executeUpdateQuery("INSERT INTO LABELS VALUES (1, 'Clôture')");
		db.executeUpdateQuery("INSERT INTO LABELS VALUES (2, '日本')");
		db.executeUpdateQuery("INSERT INTO LABELS VALUES (3, 'other')");
		Path ids = tmp.resolve("values.txt");
		Files.write(ids, ("Clôture\n日本\n").getBytes(StandardCharsets.UTF_8));
		String sql = CommandUtils.substituteMacros("DELETE FROM LABELS WHERE DESCR IN <@" + ids + ">");
		Assertions.assertEquals("DELETE FROM LABELS WHERE DESCR IN ('Clôture','日本')", sql);
	}
}
