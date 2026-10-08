package com.upandcoding.broadsql.dao;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * SPRINT 1005C (#169): the Connections Definition File ({@code $CDF}) is a protected BroadSQL system database.
 * A generic data-writing operation ({@code DUMP}/{@code PULL ... AS H2}, {@code EXPORT}) must never write into
 * it; BroadSQL's own CDF management (the vault, {@code CONFIG}, the connection commands) is not affected, since it
 * never goes through these checks.
 *
 * <p>The CDF is recognized by its physical database, not only by the {@code $CDF} name: an H2 connection URL, or
 * the CDF file name configured as {@code ServersFileName}, is reduced to the database's base path
 * ({@link #h2DatabaseBase}), and two bases are the same database when their {@code .mv.db} files are the same file
 * ({@link Files#isSameFile}), or, when a file does not exist, when their canonical paths are equal (with the
 * platform's case rule). A connection named differently that opens the same file (an alias) is therefore refused
 * too. Only a local file database can be identified: an in-memory database is never the CDF, and a server URL
 * ({@code tcp:}/{@code ssl:}) is recognized only on the local host with an absolute or {@code ~} path, since a
 * relative one depends on the server's own base directory.
 */
public final class CdfProtection {

	/** The files H2 keeps for one database; a file write to any of them is a write to the database. */
	private static final String[] DATABASE_FILE_SUFFIXES = { ".mv.db", ".h2.db", ".lock.db", ".trace.db" };

	/** H2 file-system prefixes that still designate a local file path. */
	private static final String[] FILE_SYSTEM_PREFIXES = { "file:", "nio:", "niomapped:", "async:", "retry:", "split:" };

	private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

	private CdfProtection() {
	}

	/**
	 * Refuses {@code target} as a write destination when it is the CDF: its ID is {@code $CDF}, or its URL opens the
	 * CDF's physical database. Nothing is opened or written by this check.
	 *
	 * @param commandName the user-facing command, for the message ({@code DUMP}, {@code PULL}, ...)
	 */
	public static void checkNotCdf(DatabaseDefinitionsVault vault, DatabaseDefinition target, String commandName) throws BroadSQLException {
		if (isCdf(vault, target)) {
			throw refusal(commandName, target.getId());
		}
	}

	/** Refuses the destination name {@code $CDF}, in any case, before anything is looked up or opened. */
	public static void checkNotCdfName(String connectionId, String commandName) throws BroadSQLException {
		if (SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(connectionId)) {
			throw refusal(commandName, connectionId);
		}
	}

	/** Refuses a file write ({@code EXPORT <file>}) whose file is one of the CDF database's own files. */
	public static void checkNotCdfFile(DatabaseDefinitionsVault vault, String filePath, String commandName) throws BroadSQLException {
		if (filePath == null || vault == null) {
			return;
		}
		File candidate = canonical(new File(filePath));
		for (File base : cdfBases(vault)) {
			for (String suffix : DATABASE_FILE_SUFFIXES) {
				if (sameFile(candidate, canonical(new File(base.getPath() + suffix)))) {
					throw refusal(commandName, null);
				}
			}
		}
	}

	/** Whether {@code target} is the CDF, by ID or by physical database. */
	public static boolean isCdf(DatabaseDefinitionsVault vault, DatabaseDefinition target) {
		if (target == null) {
			return false;
		}
		if (SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(target.getId())) {
			return true;
		}
		File targetBase = h2DatabaseBase(target.getUrl());
		if (targetBase == null || vault == null) {
			return false;
		}
		for (File base : cdfBases(vault)) {
			if (sameDatabase(targetBase, base)) {
				return true;
			}
		}
		return false;
	}

	private static BroadSQLException refusal(String commandName, String connectionId) {
		String subject = connectionId == null || SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(connectionId)
				? "this destination is"
				: "connection '" + connectionId + "' is";
		return new BroadSQLException(commandName + " refused: " + subject + " the BroadSQL Connections Definition File ("
				+ SpringPropertiesConfig.CDF_ID + "), a protected BroadSQL system database. It cannot be used as a write destination "
				+ "for DUMP, PULL or EXPORT. Nothing was written. Choose another destination.");
	}

	/** The CDF's base path as configured ({@code ServersFileName}) and as its {@code $CDF} connection opens it. */
	private static List<File> cdfBases(DatabaseDefinitionsVault vault) {
		List<File> bases = new ArrayList<>();
		File configured = h2DatabaseBase(vault.getFileName());
		if (configured != null) {
			bases.add(configured);
		}
		DatabaseDefinition cdf = vault.getDatabaseConnections() == null ? null : vault.getDatabaseConnection(SpringPropertiesConfig.CDF_ID);
		File registered = cdf == null ? null : h2DatabaseBase(cdf.getUrl());
		if (registered != null) {
			bases.add(registered);
		}
		return bases;
	}

	/**
	 * The canonical base path of the local H2 file database that {@code urlOrName} opens ({@code <base>.mv.db} is its
	 * file), or {@code null} when it is not one: an in-memory database, a non-H2 URL, a remote server, a blank value.
	 * Accepts a JDBC URL ({@code jdbc:h2:./conf/x;CIPHER=AES}) or the bare database name H2 takes after
	 * {@code jdbc:h2:} ({@code ./conf/x}, {@code file:~/x}). Settings after {@code ;} are ignored.
	 */
	static File h2DatabaseBase(String urlOrName) {
		if (urlOrName == null || urlOrName.isBlank()) {
			return null;
		}
		String name = urlOrName.trim();
		if (name.regionMatches(true, 0, "jdbc:", 0, 5)) {
			if (!name.regionMatches(true, 0, "jdbc:h2:", 0, 8)) {
				return null;
			}
			name = name.substring(8);
		}
		int settings = name.indexOf(';');
		if (settings >= 0) {
			name = name.substring(0, settings);
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.startsWith("tcp:") || lower.startsWith("ssl:")) {
			name = localServerPath(name.substring(4));
			if (name == null) {
				return null;
			}
		} else {
			name = stripFileSystemPrefixes(name);
			if (name == null) {
				return null;
			}
		}
		if (name.startsWith("~")) {
			name = System.getProperty("user.home") + name.substring(1);
		}
		String lowerName = name.toLowerCase(Locale.ROOT);
		if (lowerName.endsWith(".mv.db") || lowerName.endsWith(".h2.db")) {
			name = name.substring(0, name.length() - 6);
		}
		if (name.isBlank()) {
			return null;
		}
		return canonical(new File(name));
	}

	/**
	 * Removes the file-system prefixes that keep a local file path ({@code file:}, {@code nio:}, {@code split:16:}...);
	 * {@code null} when another prefix remains ({@code mem:}, {@code zip:}, {@code memFS:}...), which is not a local
	 * file database. A Windows drive letter ({@code C:}) is a path, not a prefix.
	 */
	private static String stripFileSystemPrefixes(String name) {
		boolean stripped = true;
		while (stripped) {
			stripped = false;
			for (String prefix : FILE_SYSTEM_PREFIXES) {
				if (name.regionMatches(true, 0, prefix, 0, prefix.length())) {
					name = name.substring(prefix.length());
					if ("split:".equals(prefix)) {
						name = name.replaceFirst("^\\d+:", "");
					}
					stripped = true;
				}
			}
		}
		int colon = name.indexOf(':');
		boolean driveLetter = colon == 1 && Character.isLetter(name.charAt(0));
		return colon > 0 && !driveLetter ? null : name;
	}

	/** {@code //localhost[:port]/<path>}: the path when the host is local and the path absolute or {@code ~}. */
	private static String localServerPath(String afterScheme) {
		if (!afterScheme.startsWith("//")) {
			return null;
		}
		String rest = afterScheme.substring(2);
		int slash = rest.indexOf('/');
		if (slash < 0) {
			return null;
		}
		String host = rest.substring(0, slash).toLowerCase(Locale.ROOT);
		int port = host.lastIndexOf(':');
		if (port > 0 && !host.endsWith("]")) {
			host = host.substring(0, port);
		}
		if (!LOCAL_HOSTS.contains(host)) {
			return null;
		}
		String path = rest.substring(slash + 1);
		return path.startsWith("~") || new File(path).isAbsolute() || path.startsWith("/") ? path : null;
	}

	private static boolean sameDatabase(File base, File otherBase) {
		return sameFile(canonical(new File(base.getPath() + ".mv.db")), canonical(new File(otherBase.getPath() + ".mv.db")))
				|| sameFile(base, otherBase);
	}

	/** The same file on disk when both exist; otherwise equal canonical paths ({@link File#equals}: the platform's case rule). */
	private static boolean sameFile(File a, File b) {
		if (a.exists() && b.exists()) {
			try {
				return Files.isSameFile(a.toPath(), b.toPath());
			} catch (IOException e) {
				// fall back to the path comparison
			}
		}
		return a.equals(b);
	}

	private static File canonical(File file) {
		try {
			return file.getCanonicalFile();
		} catch (IOException e) {
			return file.getAbsoluteFile();
		}
	}
}
