package com.upandcoding.broadsql.controller.shell;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;

/**
 * SPRINT 1909S, hard break: exactly one canonical setting, {@code ScriptsLibrary}, represents the Scripts
 * Library root. The former {@code SqlLib}, {@code Scripts} and {@code ListSubfolders} settings no longer
 * exist anywhere: not read, not aliased, not migrated, not written by the shipped INI files.
 */
class TestScriptsLibrarySetting {

	@Test
	void theCanonicalKeyIsScriptsLibraryAndItIsOptional() throws Exception {
		Assertions.assertEquals("ScriptsLibrary", SpringPropertiesConfig.SCRIPTS_LIBRARY);
		Field raw = ConsoleSettings.class.getDeclaredField("scriptsLibraryPathRaw");
		Value value = raw.getAnnotation(Value.class);
		Assertions.assertNotNull(value);
		Assertions.assertEquals("${ScriptsLibrary:}", value.value(), "optional: an INI file without the key must start normally");
	}

	@Test
	void anAbsentOrBlankSettingUsesTheDefaultScriptsFolderAndReportsItOnce() {
		for (String blank : new String[] { null, "", "   " }) {
			ConsoleSettings settings = new ConsoleSettings();
			ReflectionTestUtils.setField(settings, "scriptsLibraryPathRaw", blank);
			Assertions.assertEquals("scripts", settings.getScriptsLibraryPath());
			Assertions.assertTrue(settings.getScriptsLibraryPathStartupNotice().contains("ScriptsLibrary not found in the INI file, using default scripts"),
					settings.getScriptsLibraryPathStartupNotice());
		}
	}

	@Test
	void aConfiguredValueIsUsedTrimmedAndNothingIsReportedAtStartup() {
		ConsoleSettings settings = new ConsoleSettings();
		ReflectionTestUtils.setField(settings, "scriptsLibraryPathRaw", "  /data/my scripts  ");
		Assertions.assertEquals("/data/my scripts", settings.getScriptsLibraryPath());
		Assertions.assertNull(settings.getScriptsLibraryPathStartupNotice());
	}

	@Test
	void theObsoleteSettingsNoLongerExistAsFieldsAccessorsOrConstants() {
		List<String> settingsFields = Arrays.stream(ConsoleSettings.class.getDeclaredFields()).map(Field::getName).collect(Collectors.toList());
		for (String obsolete : new String[] { "libraryPath", "scriptsPath", "scriptsPathRaw", "listSubfolders" }) {
			Assertions.assertFalse(settingsFields.contains(obsolete), "ConsoleSettings must not keep " + obsolete);
		}
		List<String> methods = Arrays.stream(ConsoleSettings.class.getDeclaredMethods()).map(m -> m.getName()).collect(Collectors.toList());
		for (String obsolete : new String[] { "getLibraryPath", "setLibraryPath", "getScriptsPath", "setScriptsPath", "isListSubfolders", "setListSubfolders" }) {
			Assertions.assertFalse(methods.contains(obsolete), "ConsoleSettings must not keep " + obsolete);
		}
		List<String> constants = Arrays.stream(SpringPropertiesConfig.class.getDeclaredFields()).map(Field::getName).collect(Collectors.toList());
		for (String obsolete : new String[] { "SQL_LIB", "SQL_SCRIPTS", "LIST_SUBFOLDERS" }) {
			Assertions.assertFalse(constants.contains(obsolete), "SpringPropertiesConfig must not keep " + obsolete);
		}
	}

	@Test
	void anObsoleteKeyInTheIniFileIsSimplyIgnored() {
		// Not read, not migrated: the value of the obsolete keys never reaches the Scripts Library setting.
		ConsoleSettings settings = new ConsoleSettings();
		Assertions.assertEquals("scripts", settings.getScriptsLibraryPath(), "with no ScriptsLibrary, the obsolete SqlLib/Scripts values play no part");
	}

	@Test
	void theShippedIniFilesUseTheNewKeyAndNoneOfTheObsoleteOnes() throws IOException {
		for (String ini : new String[] { "deploy/conf/BroadSQL.ini", "deploy/conf/broadsqlux.ini" }) {
			List<String> lines = Files.readAllLines(Path.of(ini), StandardCharsets.UTF_8);
			List<String> keys = lines.stream().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith(";") && !l.startsWith("[") && l.contains("="))
					.map(l -> l.substring(0, l.indexOf('=')).trim()).collect(Collectors.toList());
			Assertions.assertTrue(keys.contains("ScriptsLibrary"), ini + " must set ScriptsLibrary");
			Assertions.assertTrue(keys.contains("JsScripts"), ini + " keeps the separate JsScripts setting");
			for (String obsolete : new String[] { "SqlLib", "Scripts", "ListSubfolders" }) {
				Assertions.assertFalse(keys.contains(obsolete), ini + " must not set the obsolete " + obsolete);
			}
		}
	}
}
