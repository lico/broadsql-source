package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.Arrays;
import java.util.ArrayList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Driver;
import java.util.List;
import java.util.Set;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.output.ConsoleUtils;
import com.upandcoding.broadsql.dao.DatabaseDriversExplorer;
import com.upandcoding.broadsql.dao.model.DatabaseDriver;

/**
 * Lists the JDBC drivers available to BroadSQL: {@code SHOW DRIVERS [DETAILED]}.
 *
 * <p>Scans every JAR file in the {@code drivers} and {@code lib} folders (relative to the current
 * working directory) for classes implementing {@code java.sql.Driver}. By default, prints one
 * summary row per driver (name, version, vendor); with the optional {@code DETAILED} argument,
 * prints a full block per driver instead, including its JAR file and driver class name(s). Ends
 * with the total number of drivers found.
 *
 * <p>This scan reads every JAR file under those two folders and can take a noticeable amount of
 * time on a large installation.
 */
public class CommandShowDrivers extends Command {

	public CommandShowDrivers() {
		super("SHOW DRIVERS", "SH DR", "SHDR");
	}

	@Override
	public void execute(String query) throws BroadSQLException {

		String[] args = parseArgs(query);

		boolean summary = true;
		if (CommandUtils.isValidArgs(args)) {
			if ("DETAILED".equalsIgnoreCase(args[0])) {
				summary = false;
			}
		}

		Path currentRelativePath = Paths.get("");
		String dirRoot = currentRelativePath.toAbsolutePath().toString();
		String path1 = dirRoot + "\\drivers";
		String path2 = dirRoot + "\\lib";
		String[] jarPath = { path1, path2 };

		console.writeln("Searching for installed JDBC drivers (long task, please wait) ...");

		DatabaseDriversExplorer explorer = new DatabaseDriversExplorer();
		List<String> files = explorer.getJarFileNames(jarPath);

		Set<DatabaseDriver> actDrivers = explorer.findClassesImplementingAlternate(files, Driver.class);

		// Summary: one table through the shared renderer (standard borders, displaymode)
		List<List<String>> summaryRows = new ArrayList<>();
		for (DatabaseDriver drvr : actDrivers) {
			if (summary) {
				summaryRows.add(Arrays.asList(drvr.getName(), drvr.getVersion(), drvr.getVendor()));
			} else {
				console.writeln("");
				console.writeln("************************************************");
				console.writeln("Driver: " + drvr.getName());
				console.writeln("Vendor: " + drvr.getVendor());
				console.writeln("Version: " + drvr.getVersion());
				console.writeln("JAR file: " + drvr.getJarFile());
				for (String cls : drvr.getClassName()) {
					console.writeln("Class name: " + cls);
				}
			}
		}

		if (summary) {
			shellConsolePrinter.printTable(List.of("Driver", "Version", "Vendor"), summaryRows, false);
		}

		int cnt = 0;
		if (actDrivers != null && !actDrivers.isEmpty()) {
			cnt = actDrivers.size();
		}
		console.writeln(" ... " + cnt + " drivers found");
	}

	@Override
	public String getDescription() {
		return ("Lists available JDBC drivers");
	}

	@Override
	public String getArguments() {
		return "DETAILED (optional)\tif specified display more details";
	}

	@Override
	public String getExamples() {
		return "SHOW DRIVERS;";
	}
}
