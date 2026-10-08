package com.upandcoding.broadsql.controller.shell.commands.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.swing.api.JApiSettingsFrame;
import com.sun.jna.Platform;

/**
 * Opens the API configuration GUI: {@code CONFIG API} (SPRINT XT02 sub-sprint 5, see the
 * "SPRINT XT02-sub sprint 5: API Configuration GUI + Bruno YAML Round-trip" design doc under docs/).
 *
 * <p>Mirrors {@link CommandConfig}'s gate exactly: Windows only, and only while connected to
 * {@code $CDF} (the encrypted Connections Definition File), the same physical file and master password an
 * {@link com.upandcoding.broadsql.dao.api.ApiDefinitionsVault} reuses for its own {@code API*} tables (see
 * that class's own javadoc), so both {@code CONFIG} and {@code CONFIG API} require exactly the same
 * connection state.
 *
 * <p>Configures BroadSQL's Universal API Client catalog end to end: APIs, environments (including the
 * prominent Base URL field, always saved through the mandatory
 * {@link com.upandcoding.broadsql.dao.api.ApiDefinitionsVault#setEnvironmentBaseUrl} dual-write path),
 * variables, headers, authentication (Inherit/None/Basic/Bearer/API Key/OAuth2 Client Credentials, plus
 * read-only display of an imported-but-unsupported type), folders and endpoints (every HTTP method, not
 * just the ones this release can execute), and each endpoint's optional BroadSQL alias (see the
 * "Amendment: Endpoint Aliases and Future Scriptability" design doc under docs/), a stable name
 * reserved for a future {@code CALL}-style script statement, never itself executable yet. Also provides
 * {@code Import Bruno YAML} and {@code Export Bruno YAML} for the same bundled OpenCollection format
 * {@code IMPORT API BRUNO} already reads; export omits secret values by default (opt-in, with an explicit
 * confirmation, to include them) and never writes an endpoint's alias, which is BroadSQL-owned local
 * metadata with no OpenCollection equivalent.
 *
 * <p>This GUI only configures APIs; it never executes a request. Execution remains {@code RUN} (API
 * Quality and UX Consolidation sprint, formerly {@code EXECUTE API ENDPOINT}); every {@code GET}/
 * {@code HEAD}/{@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE} endpoint can execute, regardless
 * of what other methods are configured here.
 *
 * <p>Changes made here take effect immediately for the CLI (the same {@code ApiDefinitionsVault}
 * instance is shared), and anything imported via {@code IMPORT API BRUNO} appears here immediately on
 * next selection, with no restart required.
 */
public class CommandConfigApi extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandConfigApi.class);

	public CommandConfigApi() {
		super("CONFIG API");
	}

	@Override
	public void execute(String qry) throws BroadSQLException {
		if (Platform.isWindows()) {
			if ("$CDF".equalsIgnoreCase(this.getPlatform())) {
				JApiSettingsFrame app = new JApiSettingsFrame();
				app.setApiDefinitionsVault(getApiDefinitionsVault());
				app.initApp();
				app.setVisible(true);
			} else {
				throw new BroadSQLException("API configuration can only be modified when connected to the CDF database");
			}
		} else {
			throw new BroadSQLException("This command is only available for Windows OS");
		}
	}

	@Override
	public String getDescription() {
		return "Displays the GUI for configuring APIs, environments, authentication, folders and endpoints (Windows only)";
	}

	@Override
	public String getDetailedDescription() {
		return "Opens the API Configuration window: manage APIs, environments (base URL, variables), authentication "
				+ "(Inherit/None/Basic/Bearer/API Key/OAuth2 Client Credentials), variables and headers, folders and "
				+ "endpoints (every HTTP method, plus an optional BroadSQL alias reserved for future script usage), and "
				+ "Bruno YAML import/export, all without hand-editing SQL or the underlying metadata tables. This GUI "
				+ "only configures APIs; it never executes a request (see RUN for that). Same "
				+ "Windows-only, $CDF-only gate as CONFIG.";
	}

	@Override
	public String getArguments() {
		return "";
	}

	@Override
	public String getExamples() {
		return "CONFIG API;";
	}
}
