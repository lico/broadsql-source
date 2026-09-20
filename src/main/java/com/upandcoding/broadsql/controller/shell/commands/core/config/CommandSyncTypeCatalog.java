package com.upandcoding.broadsql.controller.shell.commands.core.config;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;
import com.upandcoding.broadsql.dao.model.TypeDefinition;
import com.upandcoding.broadsql.dao.model.TypeSyncResult;

/**
 * Hidden maintenance command: {@code SYNC TYPE CATALOG} reconciles the CDF's {@code TYPE} table against
 * the fixed list below, which mirrors {@code releases/documentation/TECHNICAL_REQUIREMENTS.md}'s "Full
 * list of recognized database type names" one for one (same 21 IDs as
 * {@code SpringPropertiesConfig}'s {@code DBTYPE_*} constants). See {@code docs/SUPPORTED_DATABASES.md}
 * for the full design and rationale.
 *
 * <p>Every run first prints every row currently in {@code TYPE} (active or not), then inserts whichever
 * of the recognized IDs are missing and overwrites every one that already exists with the values
 * below - {@code STATUS_ID} is always reset to {@code ACTIVE} in the process. Never removes or renames a
 * row: a custom type a user added by hand, or the legacy {@code Derby} row that predates the
 * {@code DERBY Embedded}/{@code DERBY Client} split, is left untouched either way. Safe, and meant, to be
 * run again after an upgrade whenever this list changes (a new recognized type, a corrected driver class
 * name, ...) - re-running it simply refreshes every row back to the current defaults.
 *
 * <p>{@code MODE} is {@code Default} for every type except Apache Derby's two connection modes
 * ({@code Embedded}/{@code Client}).
 *
 * <p><b>An entry with no known driver class name never gets a row - not created, not updated, not even
 * left in place if one already exists from before this rule</b> (see
 * {@code DatabaseDefinitionsVault#syncTypeCatalog}, the actual enforcement point, and
 * {@code docs/TECHNICAL_CHANGE.md}, 10/09/2026, "no driver, no row" - user-reported after a first real
 * run created rows for products that don't actually work). Five of the 21 recognized names are marked
 * this way ({@code DRIVER} left {@code null} below) and are therefore always skipped: {@code JDBC-ODBC
 * Bridge} (removed from the JDK itself since Java 8), {@code Intersys} (the class name depends on which
 * InterSystems IRIS/Caché driver generation is targeted), and three long-discontinued, no-longer-usable
 * products - {@code InstantDB}, {@code Cloudscape}, {@code Pointbase}.
 */
public class CommandSyncTypeCatalog extends Command {

	private static final List<TypeDefinition> RECOGNIZED_TYPES = List.of(
			new TypeDefinition("H2", "H2 Database Engine", TypeDefinition.MODE_DEFAULT, "org.h2.Driver", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("Oracle", "Oracle Database", TypeDefinition.MODE_DEFAULT, "oracle.jdbc.OracleDriver", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("HSQL", "HyperSQL Database (HSQLDB)", TypeDefinition.MODE_DEFAULT, "org.hsqldb.jdbc.JDBCDriver", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("SQLite", "SQLite", TypeDefinition.MODE_DEFAULT, "org.sqlite.JDBC", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("DERBY Embedded", "Apache Derby (embedded, in process)", "Embedded", "org.apache.derby.jdbc.EmbeddedDriver", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("DERBY Client", "Apache Derby (network client/server)", "Client", "org.apache.derby.jdbc.ClientDriver", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("MySQL", "MySQL", TypeDefinition.MODE_DEFAULT, "com.mysql.cj.jdbc.Driver", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("MariaDB", "MariaDB", TypeDefinition.MODE_DEFAULT, "org.mariadb.jdbc.Driver", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("PostgreSQL", "PostgreSQL", TypeDefinition.MODE_DEFAULT, "org.postgresql.Driver", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("SQL Server", "Microsoft SQL Server", TypeDefinition.MODE_DEFAULT, "com.microsoft.sqlserver.jdbc.SQLServerDriver", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("DB2", "IBM Db2", TypeDefinition.MODE_DEFAULT, "com.ibm.db2.jcc.DB2Driver", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("Sybase", "Sybase / SAP ASE (Adaptive Server Enterprise)", TypeDefinition.MODE_DEFAULT, "com.sybase.jdbc42.jdbc.SybDriver", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("Informix", "IBM Informix", TypeDefinition.MODE_DEFAULT, "com.informix.jdbc.IfxDriver", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("IDS Server", "IBM Informix Dynamic Server (same driver as Informix)", TypeDefinition.MODE_DEFAULT, "com.informix.jdbc.IfxDriver", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("Firebird", "Firebird SQL", TypeDefinition.MODE_DEFAULT, "org.firebirdsql.jdbc.FBDriver", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("Teradata", "Teradata", TypeDefinition.MODE_DEFAULT, "com.teradata.jdbc.TeraDriver", DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("Intersys", "InterSystems IRIS / Cache", TypeDefinition.MODE_DEFAULT, null, DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("JDBC-ODBC Bridge", "JDBC-ODBC Bridge (removed from the JDK since Java 8)", TypeDefinition.MODE_DEFAULT, null, DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("InstantDB", "InstantDB (discontinued embedded Java database)", TypeDefinition.MODE_DEFAULT, null, DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("Cloudscape", "Cloudscape (discontinued; superseded by Apache Derby)", TypeDefinition.MODE_DEFAULT, null, DatabaseDefinition.STATUS_ACTIVE),
			new TypeDefinition("Pointbase", "PointBase (discontinued embedded Java database)", TypeDefinition.MODE_DEFAULT, null, DatabaseDefinition.STATUS_ACTIVE));

	public CommandSyncTypeCatalog() {
		super("SYNC TYPE CATALOG", "SYNC TY", "SYNCTY");
		this.hidden = true;
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		List<TypeDefinition> before = getDatabaseConnectionsVault().getTypeDetails();
		console.println("Current TYPE catalog (" + before.size() + " row(s)):");
		printTypes(before);
		console.println("");

		TypeSyncResult result = getDatabaseConnectionsVault().syncTypeCatalog(RECOGNIZED_TYPES);

		console.println(result.getAdded().isEmpty() ? "No missing type(s) to add."
				: "Added " + result.getAdded().size() + " missing type(s): " + String.join(", ", result.getAdded()));
		console.println(result.getUpdated().isEmpty() ? "No existing type(s) to refresh."
				: "Refreshed " + result.getUpdated().size() + " existing type(s) to the recognized defaults: " + String.join(", ", result.getUpdated()));
		if (!result.getSkippedNoDriver().isEmpty()) {
			console.println("Skipped " + result.getSkippedNoDriver().size() + " recognized type(s) with no known driver class "
					+ "(never created, and left untouched if already present): " + String.join(", ", result.getSkippedNoDriver()));
		}
		console.println("");
		console.println("Run SHOW DRIVERS; to check whether each type's driver class is actually available in drivers/ or lib/.");
	}

	private void printTypes(List<TypeDefinition> types) {
		if (types.isEmpty()) {
			console.println("  (empty)");
			return;
		}
		for (TypeDefinition type : types) {
			console.println("  " + StringUtils.rightPad(StringUtils.defaultString(type.getId()), 20)
					+ StringUtils.rightPad(StringUtils.defaultString(type.getMode()), 10)
					+ StringUtils.rightPad(StringUtils.defaultString(type.getDriver()), 46)
					+ StringUtils.defaultString(type.getStatusId()));
		}
	}

	@Override
	public String getDescription() {
		return ("Reconciles the CDF's TYPE table against every recognized database type name: lists what "
				+ "exists, adds whatever is missing, and refreshes every existing row to the recognized defaults.");
	}

	@Override
	public String getArguments() {
		return "(none)";
	}

	@Override
	public String getExamples() {
		return "SYNC TYPE CATALOG;";
	}
}
