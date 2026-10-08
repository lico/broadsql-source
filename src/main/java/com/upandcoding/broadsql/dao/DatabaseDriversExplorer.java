package com.upandcoding.broadsql.dao;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.model.DatabaseDriver;

public class DatabaseDriversExplorer {

	private static final Logger log = LoggerFactory.getLogger(DatabaseDriversExplorer.class);

	public List<String> getJarFileNames(String[] jarPath) {
		List<String> files = new ArrayList<>();
		for (int i = 0; i < jarPath.length; i++) {
			// Directory dirJar = new Directory(jarPath[i], false);
			// dirJar.addFilter("jar");
			// dirJar.refresh();
			// ArrayList<String> jarFiles = dirJar.getFiles();
			// log.debug("Directory: {}", jarPath[i]);
			File fileDir = new File(jarPath[i]);
			if (fileDir.exists()) {
				Collection<File> dirJar = FileUtils.listFiles(fileDir, new String[] { "jar" }, true);
				for (File file : dirJar) {
					files.add(file.getAbsolutePath());
				}
			}
		}
		return files;
	}

	public <T extends Object> List<Class<T>> findClassesImplementing(List<String> files, Class<T> cls) throws IOException {
		List<Class<T>> classes = new ArrayList<Class<T>>();
		for (String fileName : files) {
			File file = new File(fileName);
			JarFile jarFile = new JarFile(file);
			// Manifest manifest = jarFile.getManifest();
			for (JarEntry jarEntry : Collections.list(jarFile.entries())) {
				String name = jarEntry.getName();
				if (name.endsWith(".class"))
					try {
						Class<?> found = Class.forName(name.replace("/", ".").replaceAll("\\.class$", ""));
						if (cls.isAssignableFrom(found) || found.isInstance(cls) || "Driver".equalsIgnoreCase(found.getSimpleName())) {
							classes.add((Class<T>) found);
						}
					} catch (Throwable e) {
						// e.printStackTrace();
					}
			}
			if (jarFile != null) {
				jarFile.close();
			}
		}
		return classes;
	}

	public Set<DatabaseDriver> findClassesImplementingAlternate(List<String> files, Class cls) throws BroadSQLException {
		Set<DatabaseDriver> jdbcDrivers = new HashSet<>();
		for (String fileName : files) {
			File file = new File(fileName);
			JarFile jarFile;
			try {
				jarFile = new JarFile(file);
				Manifest manifest = jarFile.getManifest();
				for (JarEntry jarEntry : Collections.list(jarFile.entries())) {
					String name = jarEntry.getName();
					if (name.endsWith(".class"))
						try {
							Class found = Class.forName(name.replace("/", ".").replaceAll("\\.class$", ""));
							if (found != null && !found.isInterface()) {
								if (cls.isAssignableFrom(found) || found.isInstance(cls) || "Driver".equalsIgnoreCase(found.getSimpleName())) {
									DatabaseDriver jdbcDriver = getDriver(found);
									if (jdbcDriver != null) {
										jdbcDriver.setJarFile(jarFile.getName());
										if (manifest != null) {
											Attributes attributes = manifest.getMainAttributes();
											String vendor = attributes.getValue(Attributes.Name.IMPLEMENTATION_VENDOR);
											if (StringUtils.isNotBlank(vendor)) {
												jdbcDriver.setVendor(vendor);
											} else {
												vendor = attributes.getValue("Bundle-Vendor");
												jdbcDriver.setVendor(vendor);
											}
											String version = attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
											if (StringUtils.isNotBlank(version)) {
												jdbcDriver.setVersion(version);
											} else {
												version = attributes.getValue("Bundle-Version");
												jdbcDriver.setVersion(version);
											}
											String title = attributes.getValue(Attributes.Name.IMPLEMENTATION_TITLE);
											if (StringUtils.isNotBlank(title)) {
												jdbcDriver.setName(title);
											} else {
												title = attributes.getValue("Bundle-Name");
												if (StringUtils.isNotBlank(title)) {
													jdbcDriver.setName(title);
												}
											}
										}

										if (jdbcDrivers.contains(jdbcDriver)) {
											for (DatabaseDriver jDriver : jdbcDrivers) {
												if (jDriver.equals(jdbcDriver)) {
													jDriver.addClassName(jdbcDriver.getClassName());
												}
											}
										} else {
											jdbcDrivers.add(jdbcDriver);
										}
									}
								}
							}
						} catch (Throwable e) {
							// e.printStackTrace();
						}
				}
				if (jarFile != null) {
					jarFile.close();
				}
			} catch (IOException e1) {
				throw new BroadSQLException(e1);
			}

		}
		return jdbcDrivers;
	}

	public DatabaseDriver getDriver(Class<Driver> drivers2) {
		String driverVersion = "";
		DatabaseDriver jdbcDriver = null;
		try {
			Driver drvr = (Driver) drivers2.getDeclaredConstructor().newInstance();
			driverVersion = "" + drvr.getMajorVersion() + "." + drvr.getMinorVersion();
			jdbcDriver = new DatabaseDriver();
			jdbcDriver.addClassName(drivers2.getName());
			// jdbcDriver.setName(name);
			jdbcDriver.setJdbcCompliant(drvr.jdbcCompliant());
			jdbcDriver.setVersion(driverVersion);
		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
			e.printStackTrace();
		}
		return jdbcDriver;
	}
}
