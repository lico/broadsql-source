package com.upandcoding.broadsql.controller.shell.reader;

import java.io.ByteArrayOutputStream;
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
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandRun;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageEnvironmentEdit;
import com.upandcoding.broadsql.controller.shell.commands.core.config.CommandManageGroupEdit;
import com.upandcoding.broadsql.controller.shell.commands.core.io.CommandConnect;
import com.upandcoding.broadsql.controller.shell.commands.core.library.CommandLibEdit;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowEndpoint;
import com.upandcoding.broadsql.controller.shell.commands.core.api.CommandShowEndpoints;
import com.upandcoding.broadsql.controller.shell.commands.core.show.CommandShowGroup;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandExternalFile;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import com.upandcoding.broadsql.controller.shell.completion.EntityCompletionService;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;
import com.upandcoding.broadsql.dao.api.TestApiDefinitionsVaults;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * SPRINT 2009A - context-aware entity completion. Three layers: {@link EntityCompletionService} alone
 * (no JLine), the whole {@link BroadSqlJLineCompleter} through JLine's real {@code DefaultParser} (proves
 * entity and API completion do not leak into each other), and JLine's real key-binding dispatch over
 * piped streams (the same seam {@code TestJLineConsoleLineReaderMenuCompletion} uses; not a physical TTY).
 */
class TestEntityCompletion {

	@TempDir
	Path tempDir;

	private CommandList commandList;
	private DatabaseDefinitionsVault vault;
	private ConsoleSettings settings;

	@BeforeEach
	void setUp() throws Exception {
		commandList = new CommandList();
		for (Command command : new Command[] { new CommandConnect(), new CommandLibEdit(), new CommandExternalFile(), new CommandShowGroup(),
				new CommandManageEnvironmentEdit(), new CommandManageGroupEdit(), new CommandRun(), new CommandShowEndpoint(), new CommandShowEndpoints() }) {
			for (String keyword : command.getKeywords()) {
				commandList.put(keyword, command);
			}
		}
		vault = new DatabaseDefinitionsVault();
		vault.setIds(new TreeSet<>(List.of("CRM", "WAREHOUSE_DEV", "WAREHOUSE_PROD", "WEST")));
		vault.setGroups(new TreeSet<>(List.of("BILLING", "SALES")));
		vault.setEnvironments(new TreeSet<>(List.of("DEV", "LOCAL", "PROD")));
		settings = new ConsoleSettings();
		Files.createDirectories(tempDir.resolve("reports"));
		Files.createDirectories(tempDir.resolve("archives"));
		Files.writeString(tempDir.resolve("QR1.bsql"), "SELECT 1;");
		Files.writeString(tempDir.resolve("QR2.bsql"), "SELECT 2;");
		Files.writeString(tempDir.resolve("reports").resolve("monthly.bsql"), "SELECT 3;");
		Files.writeString(tempDir.resolve("my report.bsql"), "SELECT 4;");
		Files.writeString(tempDir.resolve("archives").resolve("QR9.bsql.20260101-120000"), "SELECT 9;");
		settings.setScriptsLibraryPath(tempDir.toString());
		commandList.setDatabaseConnectionsVault(vault);
		commandList.setShellConsoleSettings(settings);
	}

	@AfterEach
	void tearDown() {
		ApiSessionContextHolder.clear();
	}

	private static List<String> words(String line) {
		return new DefaultParser().parse(line, line.length(), org.jline.reader.Parser.ParseContext.COMPLETE).words();
	}

	private List<String> entityValues(String line) {
		List<String> w = words(line);
		return EntityCompletionService.standard(commandList).complete(w, w.size() - 1).stream().map(CompletionCandidate::getValue).collect(Collectors.toList());
	}

	// ---- connections: the reference implementation ----

	@Test
	void connectPrefixReturnsMatchingConnectionIds() {
		Assertions.assertEquals(List.of("WAREHOUSE_DEV", "WAREHOUSE_PROD", "WEST"), entityValues("CONNECT W"));
	}

	@Test
	void matchingIsCaseInsensitiveAndInsertsTheCanonicalStoredValue() {
		Assertions.assertEquals(List.of("WAREHOUSE_DEV", "WAREHOUSE_PROD"), entityValues("CONNECT war"));
	}

	@Test
	void longerPrefixNarrowsToASingleCandidate() {
		Assertions.assertEquals(List.of("WAREHOUSE_DEV"), entityValues("CONNECT WAREHOUSE_D"));
	}

	@Test
	void unmatchedAndFuzzyPrefixesYieldNothing() {
		Assertions.assertEquals(List.of(), entityValues("CONNECT ZZ"));
		Assertions.assertEquals(List.of(), entityValues("CONNECT WHS"));
		Assertions.assertEquals(List.of(), entityValues("CONNECT AREHOUSE"));
	}

	@Test
	void everyKeywordAliasOfAnEntityCommandCompletesTheSameWay() {
		Assertions.assertEquals(List.of("CRM"), entityValues("CONN CR"));
		Assertions.assertEquals(List.of("CRM"), entityValues("OPEN cr"));
	}

	@Test
	void anEmptyPrefixListsEverything() {
		Assertions.assertEquals(List.of("CRM", "WAREHOUSE_DEV", "WAREHOUSE_PROD", "WEST"), entityValues("CONNECT "));
	}

	@Test
	void argumentsPastTheDeclaredOnesAreNotCompleted() {
		Assertions.assertEquals(List.of(), entityValues("CONNECT CRM W"));
	}

	@Test
	void completionReflectsChangesWithoutARestart() {
		vault.setIds(new TreeSet<>(List.of("WAREHOUSE_QA")));
		Assertions.assertEquals(List.of("WAREHOUSE_QA"), entityValues("CONNECT W"));
	}

	// ---- environments and groups ----

	@Test
	void environmentArgumentCompletesEnvironmentsIncludingLocal() {
		Assertions.assertEquals(List.of("LOCAL"), entityValues("EDIT ENVIRONMENT L"));
		Assertions.assertEquals(List.of("PROD"), entityValues("EDIT ENV p"));
	}

	@Test
	void groupArgumentCompletesGroups() {
		Assertions.assertEquals(List.of("SALES"), entityValues("EDIT GROUP s"));
	}

	@Test
	void showGroupTakesAConnectionThenAnEnvironment() {
		Assertions.assertEquals(List.of("WEST"), entityValues("SHOW ENVIRONMENTS WE"));
		Assertions.assertEquals(List.of("DEV"), entityValues("SHOW ENVIRONMENTS WEST D"));
	}

	// ---- scripts ----

	@Test
	void libEditCompletesLibraryScriptsAsRelativePathsSkippingArchives() {
		Assertions.assertEquals(List.of("QR1.bsql", "QR2.bsql"), entityValues("LIB EDIT QR"));
		Assertions.assertEquals(List.of("reports/monthly.bsql"), entityValues("LIB EDIT rep"));
		Assertions.assertEquals(List.of(), entityValues("LIB EDIT archives"));
	}

	@Test
	void libEditAliasResolvesToTheSameArgument() {
		Assertions.assertEquals(List.of("QR1.bsql", "QR2.bsql"), entityValues("LI ED qr"));
	}

	@Test
	void atSyntaxCompletesScriptsGluedToTheKeyword() {
		Assertions.assertEquals(List.of("@QR1.bsql", "@QR2.bsql"), entityValues("@QR"));
		Assertions.assertEquals(List.of("@reports/monthly.bsql"), entityValues("@rep"));
	}

	@Test
	void aScriptNameContainingASpaceIsOfferedQuotedTheWayBroadSqlParsesIt() {
		Assertions.assertEquals(List.of("\"my report.bsql\""), entityValues("LIB EDIT my"));
		Assertions.assertEquals(List.of("@\"my report.bsql\""), entityValues("@my"));
	}

	// ---- failure handling ----

	@Test
	void aFailingSourceYieldsNoCandidatesAndNeverThrows() {
		EntityCompletionService service = new EntityCompletionService(commandList).register(CompletionEntityType.CONNECTION, () -> {
			throw new IllegalStateException("repository down");
		});
		Assertions.assertEquals(List.of(), service.complete(List.of("CONNECT", "W"), 1));
	}

	@Test
	void aMissingVaultOrLibraryYieldsNoCandidates() {
		commandList.setDatabaseConnectionsVault(null);
		Assertions.assertEquals(List.of(), entityValues("CONNECT W"));
		settings.setScriptsLibraryPath(tempDir.resolve("nope").toString());
		Assertions.assertEquals(List.of(), entityValues("LIB EDIT Q"));
	}

	@Test
	void anUnregisteredOrEmptyCommandListYieldsNothing() {
		Assertions.assertEquals(List.of(), new EntityCompletionService(new CommandList()).complete(List.of("CONNECT", "W"), 1));
		Assertions.assertEquals(List.of(), new EntityCompletionService(null).complete(List.of("CONNECT", "W"), 1));
	}

	// ---- context correctness across the whole completer (entity + API stacks) ----

	private List<String> completerValues(ApiCatalogService catalog, String line) {
		ParsedLine parsed = new DefaultParser().parse(line, line.length(), org.jline.reader.Parser.ParseContext.COMPLETE);
		BroadSqlJLineCompleter completer = new BroadSqlJLineCompleter(new CompletionService(), catalog, List::of, null, null,
				EntityCompletionService.standard(commandList));
		List<Candidate> candidates = new ArrayList<>();
		completer.complete(null, parsed, candidates);
		return candidates.stream().map(Candidate::value).collect(Collectors.toList());
	}

	private ApiCatalogService twoApis() throws BroadSQLException {
		ApiDefinitionsVault apis = TestApiDefinitionsVaults.newFileBackedVault();
		ApiVersion customer = apis.createApiWithDefaultVersion(new ApiDefinition("CUSTOMER"));
		apis.saveEndpoint(new ApiEndpoint(customer.getId(), null, "getCustomer", "GET", "${baseUrl}/customers/${id}", 0));
		ApiVersion warehouse = apis.createApiWithDefaultVersion(new ApiDefinition("WAREHOUSE"));
		apis.saveEndpoint(new ApiEndpoint(warehouse.getId(), null, "getStock", "GET", "${baseUrl}/stock/${sku}", 0));
		return new ApiCatalogService(apis);
	}

	@Test
	void connectApiOffersApiIdsNotConnectionIdsAndConnectOffersConnectionsNotApiIds() throws BroadSQLException {
		ApiCatalogService catalog = twoApis();
		Assertions.assertEquals(List.of("WAREHOUSE"), completerValues(catalog, "CONNECT API W"));
		Assertions.assertEquals(List.of("WAREHOUSE_DEV", "WAREHOUSE_PROD", "WEST"), completerValues(catalog, "CONNECT W"));
		Assertions.assertEquals(List.of("CUSTOMER"), completerValues(catalog, "CONNECT API C"));
	}

	@Test
	void runEndpointCompletionFollowsTheActiveApiSession() throws BroadSQLException {
		ApiCatalogService catalog = twoApis();
		ApiSessionContextHolder.set(new ApiSessionContext("CUSTOMER", "Test"));
		Assertions.assertEquals(List.of("GET", "/customers/:id"), completerValues(catalog, "RUN get"));
		ApiSessionContextHolder.set(new ApiSessionContext("WAREHOUSE", "Test"));
		Assertions.assertEquals(List.of("GET", "/stock/:sku"), completerValues(catalog, "RUN get"));
		ApiSessionContextHolder.clear();
		Assertions.assertEquals(List.of(), completerValues(catalog, "RUN get"));
	}

	// ---- real JLine key dispatch (piped streams, not a physical TTY) ----

	/** Feeds every keystroke string through ONE real JLine reader (each ending in a newline) and returns the submitted lines. */
	private List<String> type(String... keystrokes) throws Exception {
		PipedOutputStream keyboardOut = new PipedOutputStream();
		PipedInputStream keyboardIn = new PipedInputStream(keyboardOut);
		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTestingWithRealKeyBindings(keyboardIn, new ByteArrayOutputStream(), null, null, commandList, null);
		try {
			byte[] bytes = String.join("", keystrokes).getBytes(StandardCharsets.UTF_8);
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
			List<String> lines = new ArrayList<>();
			for (int i = 0; i < keystrokes.length; i++) {
				lines.add(reader.readLine("test> "));
			}
			return lines;
		} finally {
			reader.close();
		}
	}

	@Test
	void tabOnConnectFollowsNormalJLineBehaviour() throws Exception {
		// Ambiguous prefixes enter JLine's menu (Enter accepts the highlighted candidate, a second Enter submits).
		Assertions.assertEquals(List.of(
				"CONNECT WAREHOUSE_DEV ", // unique
				"CONNECT CRM ", // lowercase prefix, canonical stored value inserted
				"CONNECT zzz", // no match: left unchanged
				"CONNECT WAREHOUSE_DEV ", // ambiguous: first candidate
				"CONNECT WAREHOUSE_PROD "), // ambiguous: TAB again cycles
				type("CONNECT WAREHOUSE_D\t\n", "CONNECT cr\t\n", "CONNECT zzz\t\n", "CONNECT W\t\n\n", "CONNECT W\t\t\n\n"));
	}

	@Test
	void tabCompletesScriptsForAtAndLibEditIncludingANameWithASpace() throws Exception {
		Assertions.assertEquals(List.of(
				"@reports/monthly.bsql ",
				"LIB EDIT reports/monthly.bsql ",
				"LIB EDIT \"my report.bsql\" "), // quoted the way BroadSQL's own argument parser expects
				type("@rep\t\n", "LIB EDIT rep\t\n", "LIB EDIT my\t\n"));
	}

	@Test
	void existingKeywordCompletionIsUnchanged() throws Exception {
		Assertions.assertEquals(List.of("show ENDPOINT "), type("show end\t\n\n"));
	}
}
