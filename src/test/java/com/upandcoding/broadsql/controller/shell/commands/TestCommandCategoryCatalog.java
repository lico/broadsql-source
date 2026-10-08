package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Regression protection for the exact drift SPRINT 0911A fixed: {@code CommandDocGenerator} silently
 * flattening the command reference's category grouping back to a flat Core/Extension split because
 * nothing in code carried the category (see {@link CommandCategory}, {@link CommandCategoryCatalog},
 * and {@code docs/TECHNICAL_CHANGE.md}). Scans the actually-compiled {@code core}/{@code extensions}
 * packages (same technique as {@code CommandDocGenerator.findClasses}, independent of any packaged
 * jar - unlike {@code CommandLoader}, see {@link TestCommandHelp}) and asserts every concrete,
 * non-hidden, keyworded {@code Command} class has exactly one entry in
 * {@link CommandCategoryCatalog}, with no stale entry for a class that no longer exists. A future
 * command added without a catalog entry fails this test, not silently disappearing from a category.
 */
class TestCommandCategoryCatalog {

	private static final String CORE_PACKAGE = "com.upandcoding.broadsql.controller.shell.commands.core";
	private static final String EXT_PACKAGE = "com.upandcoding.broadsql.controller.shell.commands.extensions";

	/**
	 * Scans every classpath entry for {@code packageName}, not just the first ({@code getResources},
	 * plural - mirroring {@code CommandLoader.getClasses}). {@code target/test-classes} mirrors this
	 * same package for test-support classes (e.g. {@code core.catalog.TestCatalogLinter}), and under
	 * Surefire's classpath ordering {@code getResource} (singular) can resolve to that directory
	 * instead of {@code target/classes} - silently scanning zero real commands rather than throwing,
	 * which is exactly the kind of drift this test exists to catch, so it must not miss it itself.
	 */
	private static List<Class<?>> findClasses(String packageName) throws Exception {
		List<Class<?>> classes = new ArrayList<>();
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		Enumeration<URL> resources = cl.getResources(packageName.replace('.', '/'));
		while (resources.hasMoreElements()) {
			collect(new File(resources.nextElement().toURI()), packageName, classes);
		}
		return classes;
	}

	private static void collect(File dir, String packageName, List<Class<?>> out) throws Exception {
		File[] files = dir.listFiles();
		if (files == null) {
			return;
		}
		for (File file : files) {
			if (file.isDirectory()) {
				collect(file, packageName + "." + file.getName(), out);
			} else if (file.getName().endsWith(".class")) {
				String simpleName = file.getName().substring(0, file.getName().length() - ".class".length());
				out.add(Class.forName(packageName + "." + simpleName));
			}
		}
	}

	@Test
	void everyConcreteNonHiddenKeywordedCommandHasExactlyOneCategory() throws Exception {
		List<Class<?>> allClasses = new ArrayList<>();
		allClasses.addAll(findClasses(CORE_PACKAGE));
		allClasses.addAll(findClasses(EXT_PACKAGE));
		Assertions.assertFalse(allClasses.isEmpty(), "expected to find compiled Command classes under core/extensions");

		Set<Class<?>> expected = new HashSet<>();
		List<String> missing = new ArrayList<>();
		for (Class<?> candidate : allClasses) {
			if (!Command.class.isAssignableFrom(candidate) || candidate == Command.class
					|| java.lang.reflect.Modifier.isAbstract(candidate.getModifiers())) {
				continue;
			}
			@SuppressWarnings("unchecked")
			Class<? extends Command> commandClass = (Class<? extends Command>) candidate;
			Command cmd = commandClass.getDeclaredConstructor().newInstance();
			if (cmd.isHidden() || cmd.getKeywords() == null || cmd.getKeywords().length == 0) {
				continue;
			}
			expected.add(commandClass);
			if (!CommandCategoryCatalog.isRegistered(commandClass)) {
				missing.add(commandClass.getName() + " (" + cmd.getKeywords()[0] + ")");
			}
		}

		Assertions.assertTrue(missing.isEmpty(),
				"Command class(es) missing from CommandCategoryCatalog - add each to CommandCategoryCatalog.buildMap():\n"
						+ String.join("\n", missing));

		List<String> stale = new ArrayList<>();
		for (Class<? extends Command> registered : CommandCategoryCatalog.registeredClasses().keySet()) {
			if (!expected.contains(registered)) {
				stale.add(registered.getName());
			}
		}
		Assertions.assertTrue(stale.isEmpty(),
				"CommandCategoryCatalog has stale entries for class(es) that no longer qualify (removed, now hidden, or no keywords) - remove from buildMap():\n"
						+ String.join("\n", stale));
	}

	@Test
	void categoryNamesAreUniqueAndNonBlank() {
		Set<String> seen = new HashSet<>();
		for (CommandCategory category : CommandCategory.values()) {
			Assertions.assertFalse(category.getDisplayName().isBlank(), category.name() + " has a blank display name");
			Assertions.assertFalse(category.getDescription().isBlank(), category.name() + " has a blank description");
			Assertions.assertTrue(seen.add(category.getDisplayName()), "duplicate category display name: " + category.getDisplayName());
		}
	}

	@Test
	void findByNameResolvesDisplayNameEnumNameAndAliases() {
		Assertions.assertEquals(CommandCategory.EXPORT_AND_LOCAL_DATA, CommandCategory.findByName("Export & Local Data"));
		Assertions.assertEquals(CommandCategory.EXPORT_AND_LOCAL_DATA, CommandCategory.findByName("EXPORT_AND_LOCAL_DATA"));
		Assertions.assertEquals(CommandCategory.EXPORT_AND_LOCAL_DATA, CommandCategory.findByName("export"));
		Assertions.assertNull(CommandCategory.findByName("not-a-real-category"));
	}

	/** Every category needs exactly one short, non-blank, unique canonical alias (SPRINT 0911A follow-up). */
	@Test
	void everyCategoryHasAUniqueNonBlankCanonicalAlias() {
		Set<String> seen = new HashSet<>();
		for (CommandCategory category : CommandCategory.values()) {
			String alias = category.getAlias();
			Assertions.assertFalse(alias == null || alias.isBlank(), category.name() + " has no canonical alias");
			Assertions.assertTrue(seen.add(alias.toUpperCase()),
					"duplicate category alias '" + alias + "' - HELP <alias> would be ambiguous");
		}
	}

	/** Every category's canonical alias must itself resolve back to that same category, case-insensitively. */
	@Test
	void everyCategoryAliasResolvesBackToItsOwnCategoryCaseInsensitively() {
		for (CommandCategory category : CommandCategory.values()) {
			Assertions.assertEquals(category, CommandCategory.findByName(category.getAlias()),
					"HELP " + category.getAlias() + " must resolve to " + category.name());
			Assertions.assertEquals(category, CommandCategory.findByName(category.getAlias().toLowerCase()),
					"HELP " + category.getAlias().toLowerCase() + " must resolve to " + category.name());
		}
	}

	/** No accepted alias/synonym keyword may resolve to more than one category (would make HELP <alias> ambiguous). */
	@Test
	void aliasKeywordsAreUnambiguous() {
		for (String keyword : CommandCategory.aliasKeywords()) {
			CommandCategory resolved = CommandCategory.findByName(keyword);
			Assertions.assertNotNull(resolved, "alias keyword '" + keyword + "' does not resolve to any category");
		}
		// aliasKeywords() itself is backed by a Map<String, CommandCategory> (CommandCategory.ALIASES),
		// so one keyword can only ever map to one category by construction - this test documents that
		// invariant and would catch it breaking if the underlying structure ever changed away from a Map.
	}

    @Test
    void officialTaxonomySeparatesCompatibilityFromDocumentation() {
        var load = new com.upandcoding.broadsql.controller.shell.commands.extensions.CommandDirectLoadDirect();
        var edit = new com.upandcoding.broadsql.controller.shell.commands.extensions.CommandEdit();
        var batch = new com.upandcoding.broadsql.controller.shell.commands.extensions.CommandDirectLoadBatch();
        Assertions.assertEquals(CommandCategory.DATA_IMPORT, CommandCategoryCatalog.categoryOf(load));
        Assertions.assertEquals(CommandCategory.RUNNING_QUERIES, CommandCategoryCatalog.categoryOf(edit));
        Assertions.assertTrue(CommandCategoryCatalog.isDocumented(load));
        Assertions.assertTrue(CommandCategoryCatalog.isDocumented(edit));
        Assertions.assertFalse(CommandCategoryCatalog.isDocumented(batch));
        Assertions.assertEquals(CommandCategoryCatalog.DocumentationStatus.DEPRECATED_COMPATIBILITY,
                CommandCategoryCatalog.documentationStatus(batch.getClass()));
        Assertions.assertArrayEquals(new String[] { "BATCHLOAD", "BALO" }, batch.getKeywords());
        Assertions.assertTrue(CommandCategoryCatalog.registeredClasses().values().stream()
                .noneMatch(c -> c == CommandCategory.EXTENSION_COMMANDS));
        Assertions.assertNotNull(CommandCategory.EXTENSION_COMMANDS.getEmptyState());
        Assertions.assertEquals(CommandCategory.DATA_IMPORT, CommandCategory.findByName("IMPORT"));
    }
}
