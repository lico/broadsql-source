package com.upandcoding.broadsql.dao.api.bruno;

import java.util.regex.Pattern;

/**
 * Normalizes Bruno/OpenCollection's mustache-style {@code {{name}}} variable interpolation to BroadSQL's
 * own internal {@code ${name}} convention (used throughout docs/SPRINT XT02 - Universal API Client.md
 * since section 6) - see the sprint doc's section 16.3. Applied once, at the importer boundary, to every
 * imported URL/header/parameter/body/variable-value string, so the resolver and request builder
 * (sub-sprint 4) never need to know or care which external format an endpoint originally came from - the
 * same normalization a future Postman importer (also {@code {{name}}}) or OpenAPI importer
 * ({@code {param}}) would apply.
 *
 * <p>{@link #normalize} is also called directly by {@code ApiVariableSubstitutor} (SPRINT XT02-7B),
 * so a template typed by hand - not imported from Bruno at all, e.g. through {@code CONFIG API} -
 * resolves {@code {{name}}} exactly like {@code ${name}} at execution time, not only at import time.
 * This is why the method is {@code public} rather than package-private.
 */
public final class BrunoPlaceholderNormalizer {

	private static final Pattern MUSTACHE_PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*\\}\\}");
	private static final Pattern DOLLAR_PLACEHOLDER = Pattern.compile("\\$\\{\\s*([A-Za-z0-9_.\\-]+)\\s*\\}");

	private BrunoPlaceholderNormalizer() {
	}

	public static String normalize(String value) {
		if (value == null) {
			return null;
		}
		return MUSTACHE_PLACEHOLDER.matcher(value).replaceAll("\\$\\{$1\\}");
	}

	/**
	 * The reverse of {@link #normalize} - {@link BrunoCollectionExporter}'s counterpart, converting
	 * BroadSQL's internal {@code ${name}} convention back to Bruno/OpenCollection's own mustache-style
	 * {@code {{name}}} syntax. Applied at every value-writing site the exporter has (URL, header,
	 * parameter, body, variable/auth-property value) - the export mirror of every site
	 * {@link BrunoCollectionImporter} calls {@link #normalize} from. Without this, an exported collection
	 * would contain BroadSQL's {@code ${...}} syntax, which real Bruno does not recognize as an
	 * interpolation - exactly the "must generate valid current bundled OpenCollection YAML that Bruno can
	 * consume" requirement (docs/SPRINT XT02-sub sprint 5.md, section 35).
	 */
	static String denormalize(String value) {
		if (value == null) {
			return null;
		}
		return DOLLAR_PLACEHOLDER.matcher(value).replaceAll("{{$1}}");
	}
}
