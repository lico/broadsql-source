package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * SPRINT XT02B acceptance correction: {@code CommandShowApiEnvironment}'s {@code SHENV} alias silently
 * collided with the pre-existing {@code CommandShowGroup} keyword {@code SHENV}, and this was only
 * discovered at real startup ({@code CommandLoader.checkNoErrorsInKeyword} refusing to register the
 * second command), not by the test suite. This test builds the same complete keyword registry
 * {@code CommandLoader} builds at startup (every concrete, non-hidden {@code Command} class under
 * {@code core}/{@code extensions}, scanned the same way {@link TestCommandCategoryCatalog} does) and
 * asserts every canonical keyword and every alias is unique, case-insensitively, across the whole
 * catalog - so a future collision fails the build, not the user's first launch.
 */
class TestCommandKeywordRegistry {

	private static final String CORE_PACKAGE = "com.upandcoding.broadsql.controller.shell.commands.core";
	private static final String EXT_PACKAGE = "com.upandcoding.broadsql.controller.shell.commands.extensions";

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
	void everyCommandKeywordAndAliasIsGloballyUniqueCaseInsensitively() throws Exception {
		List<Class<?>> allClasses = new ArrayList<>();
		allClasses.addAll(findClasses(CORE_PACKAGE));
		allClasses.addAll(findClasses(EXT_PACKAGE));
		Assertions.assertFalse(allClasses.isEmpty(), "expected to find compiled Command classes under core/extensions");

		Map<String, String> seenKeywords = new HashMap<>();
		List<String> collisions = new ArrayList<>();

		for (Class<?> candidate : allClasses) {
			if (!Command.class.isAssignableFrom(candidate) || candidate == Command.class
					|| java.lang.reflect.Modifier.isAbstract(candidate.getModifiers())) {
				continue;
			}
			@SuppressWarnings("unchecked")
			Class<? extends Command> commandClass = (Class<? extends Command>) candidate;
			Command cmd = commandClass.getDeclaredConstructor().newInstance();
			if (cmd.isHidden()) {
				continue;
			}
			String[] keywords = cmd.getKeywords();
			if (keywords == null) {
				continue;
			}
			for (String keyword : keywords) {
				if (keyword == null || keyword.isBlank()) {
					continue;
				}
				String normalized = keyword.trim().toLowerCase();
				String owner = seenKeywords.get(normalized);
				if (owner != null && !owner.equals(commandClass.getName())) {
					collisions.add("'" + keyword + "' claimed by both " + owner + " and " + commandClass.getName());
				} else {
					seenKeywords.put(normalized, commandClass.getName());
				}
			}
		}

		Assertions.assertTrue(collisions.isEmpty(),
				"Duplicate command keyword(s)/alias(es) detected - this would fail command registration at "
						+ "startup (CommandLoader.checkNoErrorsInKeyword), not appear until the user launches "
						+ "BroadSQL:\n" + String.join("\n", collisions));
	}
}
