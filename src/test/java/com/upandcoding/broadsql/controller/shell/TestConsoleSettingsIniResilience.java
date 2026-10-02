package com.upandcoding.broadsql.controller.shell;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import com.upandcoding.broadsql.controller.config.SpringMainConfig;

/**
 * GitHub #154 - "Harden INI error handling". Root cause, confirmed empirically with a throwaway probe
 * before this fix (not by reading Spring's source): {@link ConsoleSettings} used to bind
 * {@code MaxRowXLSX}/{@code MaxRowsOnScreen}/{@code FieldsSeparator}/{@code ScreenSeparator}/
 * {@code Autocommit}/{@code IsLogActivated} directly onto primitive ({@code int}/{@code char}/
 * {@code boolean}) {@code @Value} fields. Spring's placeholder resolution converts the raw INI string to
 * the field's declared type at {@code ApplicationContext.refresh()} time, inside {@code ConsoleSettings}'
 * own bean construction - a value that fails that conversion (or a key that is missing, with no
 * {@code :default}) throws {@code UnsatisfiedDependencyException}/{@code BeanCreationException} out of
 * {@code refresh()} itself. {@code BroadSQL.main()} constructs its {@code ApplicationContext} outside its
 * own try/catch, so this was never a contained failure: a single bad property such as
 * {@code MaxRowXLSX=format} took down the entire application before a single BroadSQL class ever ran,
 * not merely Excel-export functionality.
 *
 * <p>This class proves the fix the way the defect actually manifested: a real {@link ConsoleSettings}
 * bean, constructed through the exact same {@link SpringMainConfig#placeHolderConfigurer()} production
 * uses (the pattern {@code TestSpringMainConfigPlaceholderStrictness} already established for isolating
 * Spring's placeholder behavior from a real INI file), fed a {@code MapPropertySource} standing in for the
 * INI file. {@link TestConsoleSettingsMaxRowXlsx}/{@link TestConsoleSettingsAutocommitAndLogging} (sibling
 * classes) cover every affected setting's own resolution logic directly, without a Spring context.
 */
class TestConsoleSettingsIniResilience {

	@Configuration
	static class MinimalConsoleSettingsConfig {
		@Bean
		static org.springframework.context.support.PropertySourcesPlaceholderConfigurer placeHolderConfigurer() {
			return SpringMainConfig.placeHolderConfigurer();
		}

		@Bean
		ConsoleSettings consoleSettings() {
			return new ConsoleSettings();
		}
	}

	/** Every @Value property on ConsoleSettings that still has no {@code :default} - GitHub #154 deliberately left these alone (String-typed, so no invalid-value risk; see docs/TECHNICAL_CHANGE.md). */
	private static Map<String, Object> everyRequiredProperty() {
		Map<String, Object> props = new HashMap<>();
		props.put("ServersFileName", "./conf/ConnectionsDefinitionFile.cdf");
		props.put("DefaultFolder", "c:\\temp\\");
		props.put("DefaultExtFileName", "results.xlsx");
		props.put("CustomExtensionsFolder", "extensions");
		props.put("LogFolderName", "C:\\BroadSQL\\logs");
		props.put("LogFileNamePattern", "CONSOLE");
		props.put("WinEditPlus", "");
		return props;
	}

	private ConsoleSettings bootConsoleSettings(Map<String, Object> extraProperties) {
		Map<String, Object> props = everyRequiredProperty();
		props.putAll(extraProperties);
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-ini", props));
		context.register(MinimalConsoleSettingsConfig.class);
		context.refresh();
		return context.getBean(ConsoleSettings.class);
	}

	@Test
	void anInvalidMaxRowXlsxNoLongerAbortsSpringContextConstruction() {
		ConsoleSettings settings = Assertions.assertDoesNotThrow(
				() -> bootConsoleSettings(Map.of("MaxRowXLSX", "format")),
				"a real Spring context with MaxRowXLSX=format must still construct - this used to throw "
						+ "UnsatisfiedDependencyException/BeanCreationException before GitHub #154");

		Assertions.assertEquals(ConsoleSettings.MAX_ROW_XLSX_FALLBACK, settings.getMaxRowXlsx());
		Assertions.assertEquals("format", settings.getMaxRowXlsxInvalidValue());
		Assertions.assertNotNull(settings.getMaxRowXlsxStartupNotice());
	}

	@Test
	void anUnrelatedSettingStillResolvesCorrectlyWhenMaxRowXlsxIsInvalid() {
		// The actual product requirement, not just "does not crash": unrelated configuration - including
		// another setting from this exact same hardened group - is completely unaffected.
		ConsoleSettings settings = bootConsoleSettings(Map.of("MaxRowXLSX", "format", "ScreenSeparator", ";"));

		Assertions.assertEquals(';', settings.getOnScreenSeparator());
		Assertions.assertNull(settings.getOnScreenSeparatorStartupNotice());
		Assertions.assertEquals("./conf/ConnectionsDefinitionFile.cdf", settings.getProtectedPlatformsFileName());
	}

	@Test
	void aMissingPrimitiveSettingAlsoNoLongerAbortsContextConstruction() {
		// Previously equally fatal: no ":default" meant an absent key was itself an unresolvable
		// placeholder, exactly like an invalid one.
		ConsoleSettings settings = Assertions.assertDoesNotThrow(() -> bootConsoleSettings(Map.of()));

		Assertions.assertEquals(ConsoleSettings.MAX_ROW_XLSX_FALLBACK, settings.getMaxRowXlsx());
		Assertions.assertNull(settings.getMaxRowXlsxInvalidValue(), "missing is not \"invalid\" - no coerced-value concern applies");
		Assertions.assertNotNull(settings.getMaxRowXlsxStartupNotice());
	}

	@Test
	void everyHardenedSettingCanBeSimultaneouslyInvalidWithoutAbortingStartup() {
		// "Multiple configuration problems... prefer reporting both errors rather than stopping at the
		// first one" - each setting resolves (and reports) completely independently.
		ConsoleSettings settings = bootConsoleSettings(Map.of(
				"MaxRowXLSX", "format",
				"MaxRowsOnScreen", "lots",
				"FieldsSeparator", "ab",
				"ScreenSeparator", "cd",
				"Autocommit", "maybe",
				"IsLogActivated", "1"));

		Assertions.assertEquals(ConsoleSettings.MAX_ROW_XLSX_FALLBACK, settings.getMaxRowXlsx());
		Assertions.assertEquals(ConsoleSettings.MAX_ROWS_ON_SCREEN_FALLBACK, settings.getMaxRowsOnScreen());
		Assertions.assertEquals(ConsoleSettings.DEFAULT_SEPARATOR_FALLBACK, settings.getDefaultSeparator());
		Assertions.assertEquals(ConsoleSettings.ON_SCREEN_SEPARATOR_FALLBACK, settings.getOnScreenSeparator());
		Assertions.assertEquals(ConsoleSettings.AUTOCOMMIT_FALLBACK, settings.isAutoCommit());
		Assertions.assertEquals(ConsoleSettings.LOG_DEFAULT_ACTIVATED_FALLBACK, settings.isLogDefaultActivated());

		Assertions.assertNotNull(settings.getMaxRowXlsxStartupNotice());
		Assertions.assertNotNull(settings.getMaxRowsOnScreenStartupNotice());
		Assertions.assertNotNull(settings.getDefaultSeparatorStartupNotice());
		Assertions.assertNotNull(settings.getOnScreenSeparatorStartupNotice());
		Assertions.assertNotNull(settings.getAutoCommitStartupNotice());
		Assertions.assertNotNull(settings.getLogDefaultActivatedStartupNotice());
	}

	@Test
	void aGenuinelyUndefinedUnrelatedRequiredPropertyStillFailsStartupLoudly() {
		// Regression guard, mirroring TestSpringMainConfigPlaceholderStrictness: hardening the six
		// primitive-typed settings above must not have weakened validation for the settings this sprint
		// deliberately left alone (ServersFileName and friends - see docs/TECHNICAL_CHANGE.md for why).
		Map<String, Object> incomplete = new HashMap<>(everyRequiredProperty());
		incomplete.remove("ServersFileName");

		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-ini", incomplete));
		context.register(MinimalConsoleSettingsConfig.class);
		Assertions.assertThrows(org.springframework.beans.factory.BeanCreationException.class, context::refresh);
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT 2209C (GitHub #150): JLine is the default console - activatejline through the real
	// Spring placeholder path.
	// ------------------------------------------------------------------------------------------

	@Test
	void aMissingActivateJLineKeyMeansJLineIsOn() {
		ConsoleSettings settings = bootConsoleSettings(Map.of());

		Assertions.assertTrue(settings.isJLineActivated());
		Assertions.assertNotNull(settings.getActivateJLineStartupNotice(), "a missing key is reported, as for the GitHub #154 settings");
	}

	@Test
	void anExplicitActivateJLineOnIsOnWithoutANotice() {
		ConsoleSettings settings = bootConsoleSettings(Map.of("activatejline", "ON"));

		Assertions.assertTrue(settings.isJLineActivated());
		Assertions.assertNull(settings.getActivateJLineStartupNotice());
	}

	@Test
	void anExplicitActivateJLineOffStillSelectsTheStandardConsole() {
		ConsoleSettings settings = bootConsoleSettings(Map.of("activatejline", "OFF"));

		Assertions.assertFalse(settings.isJLineActivated());
		Assertions.assertNull(settings.getActivateJLineStartupNotice());
	}

	/**
	 * The shipped INI files themselves, parsed exactly as production parses them
	 * ({@code SpringMainConfig}'s {@code @PropertySource("file:${bsql.settings}")}, i.e. Spring's own
	 * properties-file loader), not a map resembling them.
	 */
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = { "deploy/conf/BroadSQL.ini", "deploy/conf/broadsqlux.ini" })
	void theShippedIniFilesStartBroadSqlWithJLine(String iniFile) throws java.io.IOException {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.getEnvironment().getPropertySources()
				.addFirst(new org.springframework.core.io.support.ResourcePropertySource(new org.springframework.core.io.FileSystemResource(iniFile)));
		context.register(MinimalConsoleSettingsConfig.class);
		context.refresh();
		ConsoleSettings settings = context.getBean(ConsoleSettings.class);

		Assertions.assertTrue(settings.isJLineActivated(), iniFile + " must start BroadSQL with JLine");
		Assertions.assertNull(settings.getActivateJLineStartupNotice(), iniFile + " must set activatejline explicitly");
		context.close();
	}
}
