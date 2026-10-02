package com.upandcoding.broadsql.controller.shell.reader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@code HELP SHORTCUTS} and the website's "Keyboard shortcuts" section (releases/documentation/console.md)
 * describe the same keys: {@link ConsoleShortcuts} is the one list HELP prints, and this test fails as soon
 * as the documentation table or its terminal-dependent notes drift from it. The actions are worded for each
 * medium (short in the console, detailed on the website), so only the keys are compared, in order.
 * The keys' behavior itself is covered by {@code TestJLineConsoleLineReaderShortcutKeys}.
 */
class TestConsoleShortcuts {

	private static final Path CONSOLE_PAGE = Path.of("releases", "documentation", "console.md");

	private static String shortcutsSection() throws Exception {
		String page = Files.readString(CONSOLE_PAGE).replace("\r\n", "\n");
		String section = StringUtils.substringBetween(page, "## Keyboard shortcuts\n", "\n## ");
		Assertions.assertNotNull(section, "console.md has no 'Keyboard shortcuts' section");
		return section;
	}

	@Test
	void theDocumentedShortcutTableListsExactlyTheStandardShortcutsInOrder() throws Exception {
		List<String> documented = new ArrayList<>();
		for (String line : shortcutsSection().split("\n")) {
			if (line.startsWith("| ") && !line.startsWith("| Key |")) {
				documented.add(StringUtils.substringBetween(line, "| ", " |"));
			}
		}
		List<String> model = ConsoleShortcuts.STANDARD.stream().map(ConsoleShortcuts.Shortcut::keys).toList();
		Assertions.assertEquals(model, documented);
	}

	@Test
	void everyTerminalDependentShortcutIsDocumentedAsSuch() throws Exception {
		String notes = StringUtils.substringAfter(shortcutsSection(), "they are not guaranteed:");
		Assertions.assertFalse(notes.isBlank(), "console.md lost its list of terminal-dependent keys");
		for (ConsoleShortcuts.Shortcut shortcut : ConsoleShortcuts.TERMINAL_DEPENDENT) {
			Assertions.assertTrue(notes.contains(shortcut.keys()), shortcut.keys() + " is not in the terminal-dependent notes:\n" + notes);
		}
	}

	@Test
	void theWebsitePointsToHelpShortcuts() throws Exception {
		Assertions.assertTrue(shortcutsSection().contains("`HELP SHORTCUTS;`"), "console.md must tell users about HELP SHORTCUTS;");
	}

	@Test
	void definitionsAreCompleteAndUnique() {
		List<ConsoleShortcuts.Shortcut> all = new ArrayList<>(ConsoleShortcuts.STANDARD);
		all.addAll(ConsoleShortcuts.TERMINAL_DEPENDENT);
		Assertions.assertEquals(all.size(), all.stream().map(ConsoleShortcuts.Shortcut::keys).distinct().count(), "duplicate keys");
		for (ConsoleShortcuts.Shortcut shortcut : all) {
			Assertions.assertFalse(StringUtils.isAnyBlank(shortcut.keys(), shortcut.action()), shortcut.toString());
		}
	}
}
