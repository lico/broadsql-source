package com.upandcoding.broadsql.controller.shell.commands;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.output.CapturingShellConsole;
import com.upandcoding.broadsql.dao.TestDatabaseConnections;
import com.upandcoding.sampleext.SampleExtensionCommand;

/**
 * The official extension mechanism works the same on Windows and Linux: both shipped INI files name the
 * {@code extensions} folder ({@code CustomExtensionsFolder}), both launchers put {@code extensions/*} on the
 * classpath and start from the installation folder (so the relative folder is the installation's), and the command
 * loader registers the commands of a JAR found there. The Linux INI used to leave {@code CustomExtensionsFolder}
 * empty: the loader then skipped extensions although their JARs were on the Linux classpath.
 */
class TestExtensionsFolder {

	private static String setting(Path ini, String key) throws Exception {
		for (String line : Files.readAllLines(ini, StandardCharsets.UTF_8)) {
			if (line.startsWith(key + "=")) {
				return line.substring(key.length() + 1).trim();
			}
		}
		return null;
	}

	@Test
	void bothPlatformsNameTheExtensionsFolderAndPutItOnTheClasspath() throws Exception {
		Assertions.assertEquals("extensions", setting(Path.of("deploy", "conf", "BroadSQL.ini"), "CustomExtensionsFolder"));
		Assertions.assertEquals("extensions", setting(Path.of("deploy", "conf", "broadsqlux.ini"), "CustomExtensionsFolder"));
		Assertions.assertTrue(Files.readString(Path.of("deploy", "BroadSQL.bat")).contains("-cp lib/*;drivers/*;extensions/*"));
		Assertions.assertTrue(Files.readString(Path.of("deploy", "broadsql.sh")).contains("-cp \"lib/*:drivers/*:extensions/*\""));
		Assertions.assertTrue(Files.isDirectory(Path.of("deploy", "extensions")), "the folder is shipped");
	}

	/** A JAR holding {@link SampleExtensionCommand}, as a user builds one. */
	private static void writeExtensionJar(Path jar) throws Exception {
		String entry = SampleExtensionCommand.class.getName().replace('.', '/') + ".class";
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out);
				InputStream classFile = SampleExtensionCommand.class.getClassLoader().getResourceAsStream(entry)) {
			jarOut.putNextEntry(new JarEntry(entry));
			classFile.transferTo(jarOut);
			jarOut.closeEntry();
		}
	}

	@Test
	void anExtensionJarInTheInstallationsExtensionsFolderIsRegisteredWithTheShippedSetting(@TempDir Path install) throws Exception {
		for (Path ini : new Path[] { Path.of("deploy", "conf", "BroadSQL.ini"), Path.of("deploy", "conf", "broadsqlux.ini") }) {
			// The launchers start BroadSQL in the installation folder: the relative setting is resolved against it
			Path folder = Files.createDirectories(install.resolve(setting(ini, "CustomExtensionsFolder")));
			writeExtensionJar(folder.resolve("sample-extension.jar"));
			ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
			settings.setCustomExtensionsFolder(folder.toString());
			CommandLoader loader = new CommandLoader();
			loader.shellConsoleSettings = settings;
			loader.console = new CapturingShellConsole();

			Map<String, String> commands = loader.loadAvailableCommands();

			Assertions.assertEquals(CommandLoader.CMD_EXTENSION, commands.get(SampleExtensionCommand.class.getName()), ini + ": " + commands.keySet());
		}
	}

	@Test
	void anEmptySettingRegistersNoExtension(@TempDir Path install) throws Exception {
		writeExtensionJar(Files.createDirectories(install.resolve("extensions")).resolve("sample-extension.jar"));
		ConsoleSettings settings = TestDatabaseConnections.defaultConsoleSettings();
		settings.setCustomExtensionsFolder("");
		CommandLoader loader = new CommandLoader();
		loader.shellConsoleSettings = settings;
		loader.console = new CapturingShellConsole();

		Assertions.assertNull(loader.loadAvailableCommands().get(SampleExtensionCommand.class.getName()), "what the Linux INI used to do");
	}
}
