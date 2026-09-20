package com.upandcoding.broadsql.controller.shell.commands;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;

public class CommandLoader {

	private final static Logger log = LoggerFactory.getLogger(CommandLoader.class);

	@Autowired
	ConsoleSettings shellConsoleSettings;

	@Autowired
	ShellConsole console;

	public final static String CMD_CORE = "Core";
	public final static String CMD_EXTENSION = "Extension";

	private Map<String, String> allKeywords = new HashMap<>();
	private final HashMap<String, String> availableClasses = new HashMap<String, String>();

	/**
	 * Given a class and a list of existing keywords, checks whether the class
	 * contains conflicting keywords, that is keywords that already exists in the
	 * list.
	 * 
	 * @param commandClass
	 * @return
	 * @throws BroadSQLException
	 */
	public boolean checkNoErrorsInKeyword(Class commandClass) throws BroadSQLException {
		// Checks whether the keyword already exists
		boolean noError = true;
		try {
			Class classObject = Class.forName(commandClass.getCanonicalName());
			try {
				Command cmd = (Command) classObject.getDeclaredConstructor().newInstance();
				if (cmd != null && !cmd.isHidden()) {
					String[] keywords = cmd.getKeywords();
					if (keywords == null || keywords.length <= 0) {
						// Error 1 : missing keywords
						noError = false;
						String msg = "Cannot add custom command in '" + commandClass.getCanonicalName()
								+ "' due to blank or missing keywords";
						console.error(msg);
					} else {
						// Check error 2 : blank keywords
						int nbValidKeywords = 0;
						int nbInvalidKeywords = 0;
						for (String keyword : keywords) {
							// There must be at least ONE non-blank keyword
							if (StringUtils.isNotBlank(keyword)) {
								nbValidKeywords++;
							}
							// NO keyword can be blank 
							if (StringUtils.isBlank(keyword)) {
								nbInvalidKeywords++;
							}
							/*
							 * Valid characters:
							 * 	a to z, A to Z, space sign
							 */
							if (!keyword.matches("[a-zA-Z1-9 ]*")) {
								nbInvalidKeywords++;
							}

						}
						if (nbValidKeywords == 0 || nbInvalidKeywords > 0) {
							String msg = "Cannot add custom command in '" + commandClass.getCanonicalName()
									+ "' due to blank, missing or invalid keywords";
							noError = false;
							console.error(msg);
						} else {
							// Check error 3 : duplicate keywords
							for (String keyword : keywords) {
								keyword = keyword.toLowerCase();
								if (allKeywords.keySet().contains(keyword)) {
									noError = false;
									String msg = "Cannot add custom command in '" + commandClass.getCanonicalName()
											+ "' due to conflicting keyword '" + keyword + "' in '"
											+ allKeywords.get(keyword) + "'";
									console.error(msg);
									break;
								} else {
									allKeywords.put(keyword, commandClass.getCanonicalName());
								}
							}
						}
					}
				}
			} catch (InstantiationException | IllegalAccessException | IllegalArgumentException
					| InvocationTargetException | NoSuchMethodException | SecurityException ex) {
				throw new BroadSQLException(ex);
			}
		} catch (ClassNotFoundException ex) {
			throw new BroadSQLException(ex);
		}
		return noError;
	}

	/**
	 * Retrieves all classes that extend ConsoleCommand These classes are searched
	 * in 2 locations: 1) com.upandcoding.broadsql.controller 2)
	 * com.upandcoding.broadsql.extensions 3) Custom extensions if the folder exists If
	 * you want to create your own custom classes, simply create a new class in
	 * folder extensions.
	 * 
	 * @return a map of classes names grouped in core and extension classes
	 * @throws BroadSQLException
	 */
	public HashMap<String, String> loadAvailableCommands() throws BroadSQLException {
		if (!availableClasses.isEmpty()) {
			return availableClasses;
		} else {
			Class motherClass = Command.class;

			// Core BroadSQL Commands
			registerCommands(ConsoleSettings.defaultBroadSQLJarFile, shellConsoleSettings.packageNameDefault, CMD_CORE, motherClass);

			// BroadSQL Extensions Commands
			registerCommands(ConsoleSettings.defaultBroadSQLJarFile, shellConsoleSettings.packageNameExtensions, CMD_EXTENSION, motherClass);

			// User Extensions Commands
			// ************************
			String customExtensionsFolder = shellConsoleSettings.getCustomExtensionsFolder();
			if (StringUtils.isNotBlank(customExtensionsFolder)) {
				File userExtensionsFolder = new File(customExtensionsFolder);
				if (userExtensionsFolder.isDirectory()) {
					String[] supportedExtensions = new String[] { "jar" };
					// Does NOT search for jar files in subfolders as these subfolders are not in the classpath
					Collection<File> extensionFiles = FileUtils.listFiles(userExtensionsFolder, supportedExtensions, false);
					if (extensionFiles != null && !extensionFiles.isEmpty()) {
						for (File userExtensionFile : extensionFiles) {
							registerCommands(userExtensionFile.getAbsolutePath(), null, CMD_EXTENSION, motherClass);
						}
					}
				} else {
					log.warn("Custom extensions folder '{}' does not exist or is not a directory - skipping user extensions", customExtensionsFolder);
				}
			}

			return (availableClasses);
		}
	}

	/**
	 * Loads and registers the Command classes found in a single JAR file (core, bundled extension, or user
	 * extension). A JAR that fails to open, or a class within it that fails to load/instantiate, is logged
	 * and skipped rather than propagated: it must not prevent the other JARs from loading, nor BroadSQL from
	 * starting. See docs/P0-commandes.md ("A. Chargement robuste par JAR").
	 */
	private void registerCommands(String jarFileName, String packageName, String classification, Class motherClass) {
		try {
			Class[] classes = getClassesInJarFile(jarFileName, packageName);
			for (Class candidateClass : classes) {
				if (motherClass != candidateClass && motherClass.isAssignableFrom(candidateClass)) {
					try {
						boolean noConflict = checkNoErrorsInKeyword(candidateClass);
						if (noConflict) {
							availableClasses.put(candidateClass.getCanonicalName(), classification);
						}
					} catch (BroadSQLException ex) {
						String msg = "Skipping command class '" + candidateClass.getCanonicalName() + "' from '" + jarFileName + "': " + ex.getLocalizedMessage();
						log.error(msg);
						console.error(msg);
					}
				}
			}
		} catch (BroadSQLException | ClassNotFoundException | IOException ex) {
			String msg = "Unable to load commands from '" + jarFileName + "': " + ex.getLocalizedMessage();
			log.error(msg);
			console.error(msg);
		}
	}

	/**
	 * Recursive method used to find all classes in a given directory and sub
	 * directories
	 *
	 * @param directory   The base directory
	 * @param packageName The package name for classes found inside the base
	 *                    directory
	 * @return a list of classes in the directory
	 * @throws ClassNotFoundException
	 */
	private List<Class> findClasses(File directory, String packageName) throws ClassNotFoundException {
		List<Class> classes = new ArrayList<Class>();
		if (!directory.exists()) {
			return classes;
		}
		File[] files = directory.listFiles();
		for (File file : files) {
			if (file.isDirectory()) {
				assert !file.getName().contains(".");
				classes.addAll(findClasses(file, packageName + "." + file.getName()));
			} else if (file.getName().endsWith(".class")) {
				classes.add(
						Class.forName(packageName + '.' + file.getName().substring(0, file.getName().length() - 6)));
			}
		}
		return classes;
	}

	/**
	 * Extracts list of classes from a JAR file
	 * 
	 * @param jar
	 * @param classes
	 * @param jarEntry
	 * @throws BroadSQLException
	 */
	private void extractClassFromJar(final String jar, final List classes, JarEntry jarEntry) throws BroadSQLException {
		extractClassFromJar(jar, null, classes, jarEntry);
	}

	/**
	 * Extracts list of classes from a JAR file If a package name is provided then
	 * only classes in this package are considered otherwise all classes are
	 * considered.
	 * 
	 * @param jar
	 * @param packageName
	 * @param classes
	 * @param jarEntry
	 * @throws BroadSQLException
	 */
	private void extractClassFromJar(final String jar, final String packageName, final List classes, JarEntry jarEntry) {
		String className = jarEntry.getName();
		// log.debug(className);
		if (className.endsWith(".class")) {
			//System.out.println("    className: " + className);
			className = className.substring(0, className.length() - ".class".length());
			// log.debug(className);
			//System.out.println("    packageName: " + packageName);
			String altPackageName = StringUtils.replace(packageName, ".", "/");
			if (packageName == null || className.startsWith(altPackageName)) {
				// log.debug("*** " + className);
				try {
					String altClassName = StringUtils.replace(className, "/", ".");
					Class tClass = Class.forName(altClassName);
					classes.add(tClass);
				} catch (NoClassDefFoundError | ClassNotFoundException cnfe) {
					// A single incompatible class must not abort the scan of the rest of the jar
					// (see docs/P0-commandes.md, "A. Chargement robuste par JAR")
					log.warn("Skipping class '{}' within jar '{}': {}", className.replace('/', '.'), jar, cnfe.getLocalizedMessage());
				}
			}
		}
	}

	private void closeJarFile(final JarInputStream jarFile) throws BroadSQLException {
		if (jarFile != null) {
			try {
				jarFile.close();
			} catch (IOException ioe) {
				throw new BroadSQLException(ioe);
			}
		}
	}

	/**
	 * Find all classes in a JAR file, whatever their package
	 *
	 * @param directory The base directory
	 * @return a list of classes in the directory
	 * @throws ClassNotFoundException
	 */
	private List<Class> findClassesInJarFile(String jar) throws ClassNotFoundException, BroadSQLException {
		return findClassesInJarFile(jar, null);
	}

	/**
	 * Recursive method used to find all classes in a JAR file
	 *
	 * @param directory   The base directory
	 * @param packageName The package name for classes found inside the base
	 *                    directory
	 * @return a list of classes in the directory
	 * @throws ClassNotFoundException
	 */
	private List<Class> findClassesInJarFile(String jar, String packageName)
			throws ClassNotFoundException, BroadSQLException {
		List<Class> classes = new ArrayList<Class>();
		JarInputStream jarFile = null;
		try {
			//System.out.println("");
			//System.out.println("JAR: " + jar);
			jarFile = new JarInputStream(new FileInputStream(jar));
			JarEntry jarEntry;
			do {
				try {
					jarEntry = jarFile.getNextJarEntry();
					if (jarEntry != null) {
						//System.out.println("Entry: " + jarEntry.getName());
						extractClassFromJar(jar, packageName, classes, jarEntry);
					}
				} catch (IOException ioe) {
					String msg = "findClassesInJarFile: Unable to get next jar entry from jar file '" + jar + "'";
					// log.debug(msg);
					closeJarFile(jarFile);
					throw new BroadSQLException(ioe);
				}
			} while (jarEntry != null);
			closeJarFile(jarFile);
		} catch (IOException ioe) {
			String msg = "Unable to retrieve custom extensions from '" + jar + "'";
			throw new BroadSQLException(msg);
		} finally {
			closeJarFile(jarFile);
		}
		return classes;
	}

	private Class[] getClasses(String packageName) throws ClassNotFoundException, IOException {
		// log.debug("Hello ...");
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		assert classLoader != null;
		String path = packageName.replace('.', '/');
		// log.debug("Package: "+path);
		Enumeration<URL> resources = classLoader.getResources(path);
		// log.debug("Path="+path);
		List<File> dirs = new ArrayList<File>();
		while (resources.hasMoreElements()) {
			URL resource = resources.nextElement();
			dirs.add(new File(resource.getFile()));
			// log.debug("File: "+ new File(resource.getFile()).getName());
		}
		ArrayList<Class> classes = new ArrayList<Class>();
		for (File directory : dirs) {
			// log.debug(directory.getName());
			classes.addAll(findClasses(directory, packageName));
		}
		return classes.toArray(new Class[classes.size()]);
	}

	private Class[] getClassesInJarFile(String jarFile) throws ClassNotFoundException, IOException, BroadSQLException {
		return getClassesInJarFile(jarFile, null);
	}

	private Class[] getClassesInJarFile(String jarFile, String packageName)
			throws ClassNotFoundException, IOException, BroadSQLException {
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		assert classLoader != null;
		ArrayList<Class> classes = new ArrayList<Class>();
		classes.addAll(findClassesInJarFile(jarFile, packageName));
		return classes.toArray(new Class[classes.size()]);
	}

}
