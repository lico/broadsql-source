package com.upandcoding.broadsql.controller.shell.commands.core.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * Metadata for one LIB/SCRIPT catalog entry, parsed from the contiguous {@code -- @key: value}
 * header block at the very top of the file (see {@code docs/SQL_LIBRARY_AND_SCRIPTS.md}, section 3).
 * Parsing stops at the first line that is not a {@code -- @...} line, so a file with none of these
 * lines is simply untagged - every field then reports its "nothing declared" state, which keeps every
 * pre-existing library/scripts file backward compatible with no migration required.
 *
 * <p>Recognized keys: {@code @description} (first occurrence wins), {@code @instance} and
 * {@code @environment} (both repeatable <em>and</em> comma-separated on a single line - e.g.
 * {@code -- @instance: DEV,QA} declares two values exactly like two separate {@code @instance} lines
 * would - or the literal value {@code ALL}; absence of either on a given entry is the distinct
 * sentinel {@code NONE} - see {@link #instanceDisplayValue()}/
 * {@link #environmentDisplayValue()} - two fully independent scoping dimensions, mirroring the same
 * split on a connection itself: {@code @instance} is which product/system the entry is about,
 * {@code @environment} is which deployment stage it's meant to run against), {@code @tags} and
 * {@code @alias} (comma-separated, accumulated across repeated lines), {@code @status} (first
 * occurrence wins). Unknown keys are ignored, not rejected, so the format stays forward compatible.
 */
public class EntryMetadata {

	public static final String ENVIRONMENT_ALL = "ALL";
	public static final String ENVIRONMENT_NONE = "NONE";
	public static final String INSTANCE_ALL = "ALL";
	public static final String INSTANCE_NONE = "NONE";

	private final String description;
	private final List<String> instances;
	private final boolean allInstances;
	private final List<String> environments;
	private final boolean allEnvironments;
	private final List<String> tags;
	private final List<String> aliases;
	private final String status;

	private EntryMetadata(String description, List<String> instances, boolean allInstances, List<String> environments, boolean allEnvironments,
			List<String> tags, List<String> aliases, String status) {
		this.description = description;
		this.instances = Collections.unmodifiableList(instances);
		this.allInstances = allInstances;
		this.environments = Collections.unmodifiableList(environments);
		this.allEnvironments = allEnvironments;
		this.tags = Collections.unmodifiableList(tags);
		this.aliases = Collections.unmodifiableList(aliases);
		this.status = status;
	}

	public static EntryMetadata parse(String content) {
		String description = null;
		List<String> instances = new ArrayList<>();
		boolean allInstances = false;
		List<String> environments = new ArrayList<>();
		boolean allEnvironments = false;
		List<String> tags = new ArrayList<>();
		List<String> aliases = new ArrayList<>();
		String status = null;

		if (content != null) {
			try (BufferedReader br = new BufferedReader(new StringReader(content))) {
				String line;
				while ((line = br.readLine()) != null) {
					if (!isMetadataLine(line)) {
						break;
					}
					String rest = StringUtils.substringAfter(line.trim(), "--").trim(); // "@key: value"
					int colon = rest.indexOf(':');
					if (colon < 0) {
						continue;
					}
					String key = rest.substring(1, colon).trim().toLowerCase(); // substring(1) drops the leading '@'
					String value = rest.substring(colon + 1).trim();
					switch (key) {
						case "description":
							if (description == null) {
								description = value;
							}
							break;
						case "instance":
							for (String part : value.split(",")) {
								String trimmedPart = part.trim();
								if (trimmedPart.isEmpty()) {
									continue;
								} else if (trimmedPart.equalsIgnoreCase(INSTANCE_ALL)) {
									allInstances = true;
								} else {
									instances.add(trimmedPart);
								}
							}
							break;
						case "environment":
							for (String part : value.split(",")) {
								String trimmedPart = part.trim();
								if (trimmedPart.isEmpty()) {
									continue;
								} else if (trimmedPart.equalsIgnoreCase(ENVIRONMENT_ALL)) {
									allEnvironments = true;
								} else {
									environments.add(trimmedPart);
								}
							}
							break;
						case "tags":
							addCommaSeparated(tags, value);
							break;
						case "alias":
							addCommaSeparated(aliases, value);
							break;
						case "status":
							if (status == null) {
								status = value;
							}
							break;
						default:
							// Unknown key: ignored, not rejected, so the format stays forward compatible.
							break;
					}
				}
			} catch (IOException ignored) {
				// StringReader never actually throws; keeps the try-with-resources tidy.
			}
		}

		return new EntryMetadata(description, instances, allInstances, environments, allEnvironments, tags, aliases, status);
	}

	/**
	 * Returns {@code content} with the leading metadata header block removed, so callers building the
	 * SQL/BSQL body of an entry (e.g. {@code LIB RUN}) never see the {@code -- @key: value} lines as
	 * part of the executable content - important because folding them, unstripped, into a single
	 * space-joined query line (as {@code LIB RUN} already did before this feature) would turn the first
	 * {@code --} into a SQL line comment that silently swallows everything joined after it.
	 */
	public static String stripHeader(String content) {
		if (content == null) {
			return null;
		}
		StringBuilder remainder = new StringBuilder();
		try (BufferedReader br = new BufferedReader(new StringReader(content))) {
			String line;
			boolean inHeader = true;
			while ((line = br.readLine()) != null) {
				if (inHeader && isMetadataLine(line)) {
					continue;
				}
				inHeader = false;
				remainder.append(line).append("\n");
			}
		} catch (IOException ignored) {
			return content;
		}
		return remainder.toString();
	}

	private static boolean isMetadataLine(String line) {
		if (line == null) {
			return false;
		}
		String trimmed = line.trim();
		if (!trimmed.startsWith("--")) {
			return false;
		}
		return StringUtils.substringAfter(trimmed, "--").trim().startsWith("@");
	}

	private static void addCommaSeparated(List<String> target, String value) {
		for (String part : value.split(",")) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty()) {
				target.add(trimmed);
			}
		}
	}

	/**
	 * Whether this entry applies to {@code currentInstance}: always true for an entry tagged
	 * {@code ALL} or carrying no {@code @instance} tag at all (the {@code NONE} sentinel - untagged
	 * entries are never hidden by scoping), otherwise true only if one of the declared instance ids
	 * matches (case-insensitively). A blank/unknown {@code currentInstance} (no active connection, or
	 * a connection with no instance set) matches nothing specific, so a specifically-tagged entry is
	 * correctly treated as not applying. Independent of {@link #appliesToEnvironment} - a caller that
	 * wants both dimensions to match combines the two.
	 */
	public boolean appliesToInstance(String currentInstance) {
		if (allInstances || instances.isEmpty()) {
			return true;
		}
		if (StringUtils.isBlank(currentInstance)) {
			return false;
		}
		for (String instance : instances) {
			if (instance.equalsIgnoreCase(currentInstance)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether this entry applies to {@code currentEnvironment}: always true for an entry tagged
	 * {@code ALL} or carrying no {@code @environment} tag at all (the {@code NONE} sentinel - untagged
	 * entries are never hidden by scoping), otherwise true only if one of the declared environment ids
	 * matches (case-insensitively). A blank/unknown {@code currentEnvironment} (no active connection, or
	 * a connection with no environment set) matches nothing specific, so a specifically-tagged entry is
	 * correctly treated as not applying.
	 */
	public boolean appliesToEnvironment(String currentEnvironment) {
		if (allEnvironments || environments.isEmpty()) {
			return true;
		}
		if (StringUtils.isBlank(currentEnvironment)) {
			return false;
		}
		for (String environment : environments) {
			if (environment.equalsIgnoreCase(currentEnvironment)) {
				return true;
			}
		}
		return false;
	}

	/** {@code ALL}, {@code NONE}, or the comma-joined declared instance id(s), for grid display. */
	public String instanceDisplayValue() {
		if (allInstances) {
			return INSTANCE_ALL;
		}
		if (instances.isEmpty()) {
			return INSTANCE_NONE;
		}
		return String.join(",", instances);
	}

	/** {@code ALL}, {@code NONE}, or the comma-joined declared environment id(s), for grid display. */
	public String environmentDisplayValue() {
		if (allEnvironments) {
			return ENVIRONMENT_ALL;
		}
		if (environments.isEmpty()) {
			return ENVIRONMENT_NONE;
		}
		return String.join(",", environments);
	}

	public String getDescription() {
		return description;
	}

	/** Specific declared instance ids only - never contains the {@code ALL}/{@code NONE} sentinels. */
	public List<String> getInstances() {
		return instances;
	}

	public boolean isAllInstances() {
		return allInstances;
	}

	/** Specific declared environment ids only - never contains the {@code ALL}/{@code NONE} sentinels. */
	public List<String> getEnvironments() {
		return environments;
	}

	public boolean isAllEnvironments() {
		return allEnvironments;
	}

	public List<String> getTags() {
		return tags;
	}

	public List<String> getAliases() {
		return aliases;
	}

	public String getStatus() {
		return status;
	}

	public String getTagsDisplayValue() {
		return String.join(",", tags);
	}
}
