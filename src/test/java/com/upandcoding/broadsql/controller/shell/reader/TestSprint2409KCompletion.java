package com.upandcoding.broadsql.controller.shell.reader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandApiExecuteEndpoint;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandApiImportBruno;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowApiEnvironments;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowEndpoint;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageConnectionReactivate;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandDumpTable;
import com.upandcoding.broadsql.controller.shell.commands.core.export.CommandExport;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandConnect;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibEdit;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibRestore;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandExternalFile;
import com.upandcoding.broadsql.controller.shell.commands.extensions.CommandDirectLoadDirect;
import com.upandcoding.broadsql.controller.shell.completion.EntityCompletionService;
import com.upandcoding.broadsql.controller.shell.completion.FilesystemPathCompletion;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * SPRINT 2409K: the completion gaps found by the audit (inactive connections, archived Scripts, API ids and
 * session endpoints outside the {@code CONNECT API}/{@code RUN}/{@code SYNTAX}/{@code HELP} grammar), the
 * filesystem path positions, and the JLine layer: the parser now keeps backslashes, a quoted word is not
 * quoted twice, and a completed directory keeps the word open.
 */
class TestSprint2409KCompletion {

	@TempDir
	Path tempDir;

	private CommandList commandList;
	private ConsoleSettings settings;
	private Path library;
	private Path files;

	@BeforeEach
	void setUp() throws Exception {
		commandList = new CommandList();
		for (Command command : new Command[] { new CommandConnect(), new CommandLibEdit(), new CommandExternalFile(), new CommandManageConnectionReactivate(),
				new CommandLibRestore(), new CommandShowApiEnvironments(), new CommandApiExecuteEndpoint(), new CommandShowEndpoint(), new CommandApiImportBruno(),
				new CommandDirectLoadDirect(), new CommandExport(), new CommandDumpTable() }) {
			for (String keyword : command.getKeywords()) {
				commandList.put(keyword, command);
			}
		}
		DatabaseDefinitionsVault vault = new DatabaseDefinitionsVault() {
			@Override
			public List<String> getInactiveIds() {
				return List.of("OLD_CRM", "OLD_ERP");
			}
		};
		vault.setIds(new TreeSet<>(List.of("CRM", "My Conn")));
		settings = new ConsoleSettings();
		library = Files.createDirectories(tempDir.resolve("library"));
		Files.writeString(library.resolve("keep.bsql"), "SELECT 1;");
		Files.writeString(library.resolve("gone.bsql"), "SELECT 2;");
		Files.writeString(library.resolve("my report.bsql"), "SELECT 3;");
		new ScriptsLibrary(library.toString()).archive(library.resolve("gone.bsql"));
		settings.setScriptsLibraryPath(library.toString());
		files = Files.createDirectories(tempDir.resolve("files"));
		Files.createDirectories(files.resolve("scripts"));
		Files.writeString(files.resolve("scripts").resolve("run.sql"), "SELECT 4;");
		commandList.setDatabaseConnectionsVault(vault);
		commandList.setShellConsoleSettings(settings);
	}

	@AfterEach
	void tearDown() {
		ApiSessionContextHolder.clear();
	}

	private List<String> complete(String line) {
		List<String> words = JLineConsoleLineReader.newParser().parse(line, line.length(), org.jline.reader.Parser.ParseContext.COMPLETE).words();
		return EntityCompletionService.standard(commandList).complete(words, words.size() - 1).stream().map(CompletionCandidate::getValue)
				.collect(Collectors.toList());
	}

	/** Through the whole JLine adapter with BroadSQL's parser: the values JLine would insert. */
	private List<Candidate> completer(String line) {
		ParsedLine parsed = JLineConsoleLineReader.newParser().parse(line, line.length(), org.jline.reader.Parser.ParseContext.COMPLETE);
		List<Candidate> candidates = new ArrayList<>();
		new BroadSqlJLineCompleter(new CompletionService(), null, null, null, null, EntityCompletionService.standard(commandList)).complete(null, parsed, candidates);
		return candidates;
	}

	private void withApis() throws BroadSQLException {
		ApiDefinitionsVault apis = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion customer = apis.createApiWithDefaultVersion(new ApiDefinition("CUSTOMER"));
		ApiEndpoint find = new ApiEndpoint(customer.getId(), null, "findCustomer", "GET", "${baseUrl}/customers/${id}", 0);
		apis.saveEndpoint(find);
		ApiEndpoint aliased = new ApiEndpoint(customer.getId(), null, "listOrders", "GET", "${baseUrl}/orders", 0);
		aliased.setAlias("ORDERS");
		apis.saveEndpoint(aliased);
		apis.createApiWithDefaultVersion(new ApiDefinition("WAREHOUSE"));
		commandList.setApiDefinitionsVault(apis);
	}

	// ---- entity gaps ----

	@Test
	void reactivateConnectionOffersInactiveConnectionsOnly() {
		Assertions.assertEquals(List.of("OLD_CRM", "OLD_ERP"), complete("REACTIVATE CONNECTION "));
		Assertions.assertEquals(List.of("OLD_ERP"), complete("REACO old_e"));
		Assertions.assertEquals(List.of("CRM"), complete("CONNECT C"), "CONNECT still offers active connections only");
	}

	@Test
	void libRestoreOffersArchivedScriptsAndLibraryCommandsOfferLiveOnes() {
		Assertions.assertEquals(List.of("gone.bsql"), complete("LIB RESTORE "));
		Assertions.assertEquals(List.of("keep.bsql"), complete("LIB EDIT k"));
		Assertions.assertEquals(List.of(), complete("LIB EDIT g"), "an archived Script is not a live library Script");
	}

	@Test
	void apiIdsAndSessionEndpointsInTheirOwnCommandsOnly() throws Exception {
		withApis();
		Assertions.assertEquals(List.of("CUSTOMER", "WAREHOUSE"), complete("SHOW API ENVIRONMENTS "));
		Assertions.assertEquals(List.of("WAREHOUSE"), complete("EXECUTE API ENDPOINT w"));
		Assertions.assertEquals(List.of(), complete("EXECUTE API ENDPOINT CUSTOMER "), "the endpoint belongs to the named API, not completed");
		Assertions.assertEquals(List.of(), complete("SHOW ENDPOINT "), "no API session: nothing");
		ApiSessionContextHolder.set(new ApiSessionContext("CUSTOMER", "Test"));
		Assertions.assertEquals(List.of("findCustomer", "ORDERS"), complete("SHOW ENDPOINT "), "alias when set, name otherwise");
		Assertions.assertEquals(List.of("ORDERS"), complete("SHEND or"));
		Assertions.assertEquals(List.of(), complete("CONNECT CUS"), "API ids never leak into CONNECT");
	}

	// ---- filesystem path positions ----

	@Test
	void dumpAtScriptCompletesScriptsLibraryNamesLikeDumpLib() {
		Assertions.assertEquals(List.of("@keep.bsql"), complete("DUMP @ke"));
		Assertions.assertEquals(List.of("keep.bsql"), complete("DUMP LIB ke"), "the same names as DUMP LIB");
		Assertions.assertEquals(List.of(), complete("DUMP @go"), "an archived Script is not offered");
	}

	@Test
	void pathPositionsCompleteFromTheFilesystem() {
		String base = files.toAbsolutePath().toString() + File.separator;
		Assertions.assertEquals(List.of(base + "scripts" + File.separator), complete("IMPORT API BRUNO " + base + "sc"));
		Assertions.assertEquals(List.of(base + "scripts" + File.separator), complete("LOAD ORDERS " + base + "sc"));
		Assertions.assertEquals(List.of(), complete("LOAD " + base + "sc"), "the first LOAD argument is a table, not a file");
		Assertions.assertEquals(List.of(base + "scripts" + File.separator), complete("EXPORT " + base + "sc"));
		Assertions.assertEquals(List.of("@" + base + "scripts" + File.separator), complete("@" + base + "sc"), "an absolute @ path is a file");
		Assertions.assertEquals(List.of("@keep.bsql"), complete("@ke"), "a bare @ reference is still a Scripts Library entry");
		Assertions.assertEquals(List.of("<@" + base + "scripts" + File.separator), complete("SELECT * FROM T WHERE X > 1 AND ID IN <@" + base + "sc"));
		Assertions.assertEquals(List.of("<@csv:" + base + "scripts" + File.separator), complete("SELECT 1 FROM T WHERE ID IN <@csv:" + base + "sc"));
		Assertions.assertEquals(List.of(), complete("SELECT 1 FROM T WHERE ID IN <@last:I"), "<@last:...> is not a path");
	}

	@Test
	void theParserKeepsBackslashesSoWindowsPathsReachTheCompleterIntact() {
		List<String> words = JLineConsoleLineReader.newParser().parse("@C:\\temp\\a", 10, org.jline.reader.Parser.ParseContext.COMPLETE).words();
		Assertions.assertEquals(List.of("@C:\\temp\\a"), words);
		words = JLineConsoleLineReader.newParser().parse("LOAD T \"C:\\My Dir\\x", 19, org.jline.reader.Parser.ParseContext.COMPLETE).words();
		Assertions.assertEquals(List.of("LOAD", "T", "C:\\My Dir\\x"), words, "quotes still group a path with spaces");
	}

	// ---- JLine adapter ----

	@Test
	void aWordTheUserOpenedWithAQuoteIsNotQuotedTwice() {
		List<Candidate> quoted = completer("CONNECT \"My");
		Assertions.assertEquals(List.of("My Conn"), quoted.stream().map(Candidate::value).collect(Collectors.toList()), "JLine re-adds the user's own quote");
		List<Candidate> unquoted = completer("CONNECT My");
		Assertions.assertEquals(List.of("\"My Conn\""), unquoted.stream().map(Candidate::value).collect(Collectors.toList()));
		Assertions.assertEquals(List.of("\"my report.bsql\""), completer("LIB EDIT my").stream().map(Candidate::value).collect(Collectors.toList()));
	}

	@Test
	void aDirectoryCandidateKeepsTheWordOpenAndAFileClosesIt() {
		String base = files.toAbsolutePath().toString() + File.separator;
		List<Candidate> directory = completer("IMPORT API BRUNO " + base + "sc");
		Assertions.assertFalse(directory.get(0).complete(), "no trailing space after a directory");
		List<Candidate> file = completer("IMPORT API BRUNO " + base + "scripts" + File.separator + "r");
		Assertions.assertTrue(file.get(0).complete());
	}

	/** Real JLine key dispatch over piped streams (explicit terminal type: no slow terminal detection). */
	private String type(String keystrokes) throws Exception {
		PipedOutputStream keyboardOut = new PipedOutputStream();
		PipedInputStream keyboardIn = new PipedInputStream(keyboardOut);
		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTestingWithRealKeyBindings(keyboardIn, new ByteArrayOutputStream(), null, null, commandList, null,
				"xterm");
		try {
			byte[] bytes = keystrokes.getBytes(StandardCharsets.UTF_8);
			Thread feeder = new Thread(() -> {
				try {
					for (byte b : bytes) {
						keyboardOut.write(b);
						keyboardOut.flush();
						Thread.sleep(5);
					}
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			feeder.setDaemon(true);
			feeder.start();
			return reader.readLine("test> ");
		} finally {
			reader.close();
		}
	}

	@Test
	void tabCompletesAPathWithSingleSeparatorsThroughRealKeyDispatch() throws Exception {
		String base = files.toAbsolutePath().toString() + File.separator;
		Assertions.assertEquals("@" + base + "scripts" + File.separator + "run.sql ", type("@" + base + "sc\trun\t\n"),
				"directory completion keeps the word open; the file then completes and closes it");
		String typed = type("SELECT * FROM T WHERE ID IN <@" + base + "sc\t\n");
		Assertions.assertEquals("SELECT * FROM T WHERE ID IN <@" + base + "scripts" + File.separator, typed);
		Assertions.assertFalse(typed.contains(File.separator + File.separator), "no doubled separator: " + typed);
		Assertions.assertTrue(FilesystemPathCompletion.isExplicitFilesystemReference(base));
	}
}
