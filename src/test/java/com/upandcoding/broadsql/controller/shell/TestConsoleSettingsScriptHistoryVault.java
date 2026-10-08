package com.upandcoding.broadsql.controller.shell;

import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** SPRINT 0917-01 - {@link ConsoleSettings#resolveScriptHistoryVaultPath()}, same three-way resolution rule as {@code resolveJLineHistoryFilePath()} (see {@code TestConsoleSettingsXt02a}), with its own default subfolder. */
class TestConsoleSettingsScriptHistoryVault {

	@Test
	void blankOrAbsentResolvesToTheDefaultScriptHistorySubfolder() {
		ConsoleSettings settings = new ConsoleSettings();
		Path resolved = settings.resolveScriptHistoryVaultPath();
		Assertions.assertEquals(Path.of(System.getProperty("user.home"), ".broadsql", "script-history"), resolved);

		settings.setScriptHistoryVaultRaw("   ");
		Assertions.assertEquals(Path.of(System.getProperty("user.home"), ".broadsql", "script-history"), settings.resolveScriptHistoryVaultPath());
	}

	@Test
	void neverCollidesWithTheJLineHistoryFileDefault() {
		ConsoleSettings settings = new ConsoleSettings();
		Assertions.assertNotEquals(settings.resolveJLineHistoryFilePath(), settings.resolveScriptHistoryVaultPath());
	}

	@Test
	void anAbsolutePathIsUsedExactlyAsGiven() {
		ConsoleSettings settings = new ConsoleSettings();
		Path absolute = Path.of(System.getProperty("java.io.tmpdir"), "broadsql-0917-01-test", "custom-vault").toAbsolutePath();
		settings.setScriptHistoryVaultRaw(absolute.toString());

		Assertions.assertEquals(absolute, settings.resolveScriptHistoryVaultPath());
	}

	@Test
	void aRelativePathResolvesUnderTheUserHomeDotBroadsqlDirectory() {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setScriptHistoryVaultRaw("my-vault");

		Assertions.assertEquals(Path.of(System.getProperty("user.home"), ".broadsql", "my-vault"), settings.resolveScriptHistoryVaultPath());
	}
}
