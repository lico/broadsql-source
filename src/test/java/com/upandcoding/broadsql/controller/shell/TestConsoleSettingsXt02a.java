package com.upandcoding.broadsql.controller.shell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SPRINT XT02A (URL-Native API Execution) - the activatejline/apiproxy* INI settings surface. */
class TestConsoleSettingsXt02a {

	// SPRINT 2209C (GitHub #150) superseded the original "OFF by default" and "anything other than ON is
	// OFF" rules: JLine is now the default console and only an explicit OFF disables it.

	@Test
	void jLineIsOnByDefault() {
		ConsoleSettings settings = new ConsoleSettings();
		Assertions.assertTrue(settings.isJLineActivated());
	}

	@Test
	void jLineOnIsCaseInsensitive() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setActivateJLineRaw("on");
		Assertions.assertTrue(settings.isJLineActivated());
		Assertions.assertNull(settings.getActivateJLineStartupNotice());
	}

	@Test
	void jLineOffIsCaseInsensitiveAndDisablesJLine() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setActivateJLineRaw(" off ");
		Assertions.assertFalse(settings.isJLineActivated());
		Assertions.assertNull(settings.getActivateJLineStartupNotice());
	}

	@Test
	void anUnrecognizedValueFallsBackToOnWithANotice() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setActivateJLineRaw("bogus");
		Assertions.assertTrue(settings.isJLineActivated());
		Assertions.assertTrue(settings.getActivateJLineStartupNotice().contains("bogus"));
	}

	@Test
	void aBlankValueIsTheDefaultWithANotice() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setActivateJLineRaw("  ");
		Assertions.assertTrue(settings.isJLineActivated());
		Assertions.assertNotNull(settings.getActivateJLineStartupNotice());
	}

	@Test
	void theSetterReplacesAnEarlierResolution() {
		ConsoleSettings settings = new ConsoleSettings();
		Assertions.assertTrue(settings.isJLineActivated());
		settings.setActivateJLineRaw("OFF");
		Assertions.assertFalse(settings.isJLineActivated());
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02B, section 14: jlinehistoryfile resolution - deliberately per OS user, never
	// install-relative.
	// ------------------------------------------------------------------------------------------

	@Test
	void blankOrAbsentResolvesUnderTheUserHomeDotBroadsqlDirectory() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setJLineHistoryFileRaw(null);
		Path resolved = settings.resolveJLineHistoryFilePath();
		Assertions.assertEquals(Path.of(System.getProperty("user.home"), ".broadsql", "history"), resolved);

		settings.setJLineHistoryFileRaw("   ");
		Assertions.assertEquals(Path.of(System.getProperty("user.home"), ".broadsql", "history"), settings.resolveJLineHistoryFilePath());
	}

	@Test
	void anAbsolutePathIsUsedExactlyAsGiven() {
		ConsoleSettings settings = new ConsoleSettings();
		Path absolute = Path.of(System.getProperty("java.io.tmpdir"), "broadsql-xt02b-history-test", "custom-history").toAbsolutePath();
		settings.setJLineHistoryFileRaw(absolute.toString());

		Assertions.assertEquals(absolute, settings.resolveJLineHistoryFilePath());
	}

	@Test
	void aRelativePathResolvesUnderTheUserHomeDotBroadsqlDirectoryNeverInstallRelative() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setJLineHistoryFileRaw("myhistory");

		Path resolved = settings.resolveJLineHistoryFilePath();

		Assertions.assertEquals(Path.of(System.getProperty("user.home"), ".broadsql", "myhistory"), resolved);
	}

	@Test
	void aRelativePathWithSubdirectoriesStillResolvesUnderTheUserHomeDotBroadsqlDirectory() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setJLineHistoryFileRaw("sub/dir/myhistory");

		Path resolved = settings.resolveJLineHistoryFilePath();

		Assertions.assertEquals(Path.of(System.getProperty("user.home"), ".broadsql", "sub", "dir", "myhistory"), resolved);
	}

	@Test
	void proxySettingsRoundTrip() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setApiProxyMode("MANUAL");
		settings.setApiProxyHost("proxy.corp.example");
		settings.setApiProxyPort("8080");

		Assertions.assertEquals("MANUAL", settings.getApiProxyMode());
		Assertions.assertEquals("proxy.corp.example", settings.getApiProxyHost());
		Assertions.assertEquals("8080", settings.getApiProxyPort());
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02A corrective pass (16/09/2026): apiproxyusername/apiproxypassword are read directly
	// from the raw INI file, bypassing Spring's "@Value"/placeholder mechanism entirely, so a literal
	// "${ENV:NAME}" reference in the INI file can never be mistaken by Spring for one of its own
	// placeholders (see ConsoleSettings' own javadoc and docs/TECHNICAL_CHANGE.md).
	// ------------------------------------------------------------------------------------------

	@Test
	void apiProxyCredentialsAreReadFromTheRawIniFileNotSpring(@TempDir Path tempDir) throws IOException {
		Path ini = tempDir.resolve("BroadSQL.ini");
		Files.writeString(ini, "apiproxyusername=${ENV:BROADSQL_PROXY_USER}\napiproxypassword=literal-pass\n", StandardCharsets.UTF_8);

		ConsoleSettings settings = new ConsoleSettings();
		settings.setIniFileName(ini.toString());

		Assertions.assertEquals("${ENV:BROADSQL_PROXY_USER}", settings.getApiProxyUsername(),
				"the literal ${ENV:...} text must survive completely unresolved - EnvVarResolver resolves it later, not Spring, not this class");
		Assertions.assertEquals("literal-pass", settings.getApiProxyPassword());
	}

	@Test
	void apiProxyCredentialsAreNullWhenTheIniFileHasNoSuchKeys(@TempDir Path tempDir) throws IOException {
		Path ini = tempDir.resolve("BroadSQL.ini");
		Files.writeString(ini, "[General]\nDefaultFolder=c:\\\\temp\\\\\n", StandardCharsets.UTF_8);

		ConsoleSettings settings = new ConsoleSettings();
		settings.setIniFileName(ini.toString());

		Assertions.assertNull(settings.getApiProxyUsername());
		Assertions.assertNull(settings.getApiProxyPassword());
	}

	@Test
	void anExplicitlySetValueSkipsTheFileReadEntirely() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setIniFileName("this/path/does/not/exist.ini");
		settings.setApiProxyUsername("explicit-user");

		Assertions.assertEquals("explicit-user", settings.getApiProxyUsername());
	}
}
