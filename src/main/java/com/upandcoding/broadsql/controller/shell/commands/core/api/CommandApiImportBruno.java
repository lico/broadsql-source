package com.upandcoding.broadsql.controller.shell.commands.core.api;

import java.io.File;
import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.dao.api.bruno.BrunoCollectionImporter;
import com.upandcoding.broadsql.dao.api.bruno.BrunoImportResult;
import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;

/**
 * Imports a bundled OpenCollection YAML (Bruno) API collection into BroadSQL's API catalog:
 * {@code IMPORT API BRUNO <filePath> <apiId>}. See the "SPRINT XT02: Universal API Client" design doc
 * under docs/.
 *
 * <p>Creates {@code apiId} (with its mandatory default version) the first time it is used; re-running
 * against an already-imported {@code apiId} is a safe, idempotent re-import: an object already known by
 * its source identity is updated, a new one is inserted, an object from a previous import no longer
 * present in the source is left completely untouched, never deleted (sprint doc requirement 7). Every
 * HTTP verb is imported and becomes visible via {@code SHOW ENDPOINTS}; this release executes every
 * verb except {@code OPTIONS} (see {@code RUN}). Scripts, assertions and pre/post request automation
 * are recognized during import and counted, never persisted or shown.
 *
 * <p>Only the bundled ({@code bundled: true}) single-file OpenCollection format is supported in this
 * release: the format the user currently exports from Bruno. Directory-based (multi-file) collections
 * are not yet read.
 */
public class CommandApiImportBruno extends Command {

	public CommandApiImportBruno() {
		super("IMPORT API BRUNO");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] args = parseArgs(query);
		if (args == null || args.length < 2) {
			console.error("Usage: " + getExamples());
			return;
		}
		String filePath = args[0];
		String apiId = args[1].toUpperCase();
		File file = new File(filePath);
		if (!file.isFile()) {
			console.error("File not found: '" + filePath + "'");
			return;
		}

		BrunoImportResult result = new BrunoCollectionImporter(getApiDefinitionsVault()).importFile(apiId, file);

		console.println("");
		console.println("API imported: " + getApiDefinitionsVault().getApi(apiId).getName());
		console.println("Environments: created " + result.getEnvironmentsCreated() + ", updated " + result.getEnvironmentsUpdated());
		console.println("Folders: created " + result.getGroupsCreated() + ", updated " + result.getGroupsUpdated());
		console.println("Endpoints: created " + result.getEndpointsCreated() + ", updated " + result.getEndpointsUpdated());
		if (result.getRuntimeFeaturesIgnored() > 0) {
			console.println("Ignored unsupported Bruno runtime items (scripts/assertions/actions): " + result.getRuntimeFeaturesIgnored());
		}
		if (!result.getSkippedItemTypes().isEmpty()) {
			console.println("Skipped unsupported collection item types: " + result.getSkippedItemTypes().size());
		}
		if (!result.getUnsupportedAuthTypes().isEmpty()) {
			console.println("Authentication types imported but not executable in this release: " + String.join(", ", result.getUnsupportedAuthTypes()));
		}
		console.println("");
	}

	@Override
	public String getDescription() {
		return "Imports a bundled OpenCollection YAML (Bruno) API collection";
	}

	@Override
	public String getDetailedDescription() {
		return "Imports a bundled OpenCollection YAML (Bruno) API collection into BroadSQL's API catalog: "
				+ "creates the API (with environments, folders, and endpoints of every HTTP method) if it does "
				+ "not exist yet, or safely re-imports into an existing one: an object present in the source is "
				+ "created or updated, an object from a previous import no longer present in the source is left "
				+ "untouched, never deleted. Every HTTP verb is imported and listed by SHOW ENDPOINTS; GET, "
				+ "HEAD, POST, PUT, PATCH, and DELETE can be executed (RUN refuses OPTIONS and "
				+ "any other verb). Scripts, assertions and request/response automation are never imported. Only "
				+ "the bundled (single-file) OpenCollection YAML format is supported.";
	}

	@Override
	public String getArguments() {
		return "<filePath> (mandatory) path to a bundled OpenCollection YAML file, quote if it contains spaces; "
				+ "<apiId> (mandatory) the API identifier to create or re-import into";
	}

	@Override
	public String getExamples() {
		return "IMPORT API BRUNO \"C:\\bruno\\demo.yml\" DEMO;";
	}

	/** SPRINT 2409K: the one argument is a file path (absolute, or relative to the working directory). */
	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.FILE_PATH);
	}
}
