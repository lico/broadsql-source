package com.upandcoding.broadsql.controller.shell.commands.core.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.shell.sql.SqlLexException;
import com.upandcoding.broadsql.controller.shell.sql.SqlToken;
import com.upandcoding.broadsql.controller.shell.sql.SqlTokenType;
import com.upandcoding.broadsql.controller.shell.sql.SqlTokenizer;

/**
 * Metadata for one LIB/SCRIPT catalog entry, parsed from {@code @key value}/{@code @key: value}
 * directives found in valid SQL comments <b>anywhere</b> in the file - not only a leading header
 * block (see {@code releases/documentation/library_and_scripts.md}, section 3, and SPRINT 0917-01's corrective
 * acceptance pass). Detection is comment-aware, built on the existing {@link SqlTokenizer}: a
 * directive is only ever recognized inside a {@code --} line comment or a {@code /* ... *}{@code /}
 * block comment, never inside a string literal or ordinary SQL text - {@code SELECT '@instance
 * WRONG';} is never treated as metadata.
 *
 * <p>Two directive syntaxes are both recognized, on the same line, in either comment style:
 * {@code -- @instance MYWORLD} (space-separated, the primary form real BroadSQL files use) and
 * {@code -- @instance: MYWORLD} (colon-separated, the form the Editor writes for a brand-new Script). Neither
 * requires migrating existing files.
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
 *
 * <p>{@link #extractHeaderLines(String)}/{@link #stripHeader(String)} remain scoped to the leading
 * contiguous comment-or-blank region only (the canonical read/write location the Script Library's
 * metadata panel patches - see {@code ScriptMetadataHeader}) - a directive declared later in the
 * document is still found by {@link #parse(String)} for display/{@code LIB LIST}/validation
 * purposes, but editing it through the GUI adds/updates the header block rather than rewriting an
 * arbitrary later occurrence in place. This is a deliberate, bounded scope decision, not an
 * oversight - see {@code docs/TECHNICAL_CHANGE.md}'s corrective-pass entry for SPRINT 0917-01.
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
	private final List<String> declaredParams;

	private EntryMetadata(String description, List<String> instances, boolean allInstances, List<String> environments, boolean allEnvironments,
			List<String> tags, List<String> aliases, String status, List<String> declaredParams) {
		this.description = description;
		this.instances = Collections.unmodifiableList(instances);
		this.allInstances = allInstances;
		this.environments = Collections.unmodifiableList(environments);
		this.allEnvironments = allEnvironments;
		this.tags = Collections.unmodifiableList(tags);
		this.aliases = Collections.unmodifiableList(aliases);
		this.status = status;
		this.declaredParams = Collections.unmodifiableList(declaredParams);
	}

	/**
	 * One recognized {@code @key ...} directive found on a single physical line (inside a line
	 * comment, inside a block comment, or - in the unlexable-content fallback - a bare raw line).
	 * Exposed ({@code public}) for the Script Library's round-trip patcher ({@code
	 * ScriptMetadataHeader}) and {@code MetadataDirectiveEditor}, so neither reimplements its own
	 * competing line-level directive grammar - see this class's own javadoc.
	 */
	public static final class DirectiveLine {
		private final String key;
		private final String value;
		private final boolean hasSeparator;
		private final int atIndex;

		private DirectiveLine(String key, String value, boolean hasSeparator, int atIndex) {
			this.key = key;
			this.value = value;
			this.hasSeparator = hasSeparator;
			this.atIndex = atIndex;
		}

		/** Lowercase key name, e.g. {@code "instance"} - never blank (a bare {@code "@"} with no key text is not a directive at all). */
		public String key() {
			return key;
		}

		/** The trimmed value text, or {@code ""} if none was written. */
		public String value() {
			return value;
		}

		/** Whether a separator (colon or whitespace) was found after the key at all - {@code false} only for a completely bare {@code @key} with nothing following it. */
		public boolean hasSeparator() {
			return hasSeparator;
		}

		/** The character offset of {@code '@'} within the original raw line - where the key name begins (one past this). */
		public int atIndex() {
			return atIndex;
		}
	}

	public static EntryMetadata parse(String content) {
		Accumulator acc = new Accumulator();
		if (content != null) {
			try {
				for (SqlToken token : SqlTokenizer.tokenize(content)) {
					if (token.getType() == SqlTokenType.LINE_COMMENT) {
						applyDirectiveLineIfAny(token.getText(), acc);
					} else if (token.getType() == SqlTokenType.BLOCK_COMMENT) {
						for (String innerLine : blockCommentInnerLines(token.getText())) {
							applyDirectiveLineIfAny(innerLine, acc);
						}
					}
				}
			} catch (SqlLexException e) {
				// The document can't be safely tokenized (an unterminated string/comment elsewhere) -
				// fail open rather than lose all metadata: scan every raw physical line directly. A
				// directive marker ('@') is only ever recognized at a line's own start (after optional
				// "--"/"*" comment-marker stripping - see atMarkerIndex), so a fake "@key" embedded
				// mid-line inside real SQL/string text still is not mistaken for a directive here.
				for (String rawLine : splitLines(content)) {
					applyDirectiveLineIfAny(rawLine, acc);
				}
			}
		}
		return acc.build();
	}

	private static void applyDirectiveLineIfAny(String rawLineOrCommentText, Accumulator acc) {
		DirectiveLine directive = analyzeDirectiveLine(rawLineOrCommentText);
		if (directive == null) {
			return;
		}
		String value = directive.value();
		switch (directive.key()) {
			case "description":
				if (acc.description == null) {
					acc.description = value;
				}
				break;
			case "instance":
				for (String part : value.split(",")) {
					String trimmedPart = part.trim();
					if (trimmedPart.isEmpty()) {
						continue;
					} else if (trimmedPart.equalsIgnoreCase(INSTANCE_ALL)) {
						acc.allInstances = true;
					} else {
						acc.instances.add(trimmedPart);
					}
				}
				break;
			case "environment":
				for (String part : value.split(",")) {
					String trimmedPart = part.trim();
					if (trimmedPart.isEmpty()) {
						continue;
					} else if (trimmedPart.equalsIgnoreCase(ENVIRONMENT_ALL)) {
						acc.allEnvironments = true;
					} else {
						acc.environments.add(trimmedPart);
					}
				}
				break;
			case "tags":
				addCommaSeparated(acc.tags, value);
				break;
			case "alias":
				addCommaSeparated(acc.aliases, value);
				break;
			case "status":
				if (acc.status == null) {
					acc.status = value;
				}
				break;
			case "params":
				// SPRINT 0110A: -- @params: a, b (or @params a, b), repeatable, names accumulate. Names are kept as
				// written, empty items ignored; validity is checked by the Script preflight and LIB LINT.
				addCommaSeparated(acc.params, value);
				break;
			default:
				// Unknown key: ignored, not rejected, so the format stays forward compatible.
				break;
		}
	}

	private static final class Accumulator {
		String description;
		final List<String> instances = new ArrayList<>();
		boolean allInstances;
		final List<String> environments = new ArrayList<>();
		boolean allEnvironments;
		final List<String> tags = new ArrayList<>();
		final List<String> aliases = new ArrayList<>();
		String status;
		final List<String> params = new ArrayList<>();

		EntryMetadata build() {
			return new EntryMetadata(description, instances, allInstances, environments, allEnvironments, tags, aliases, status, params);
		}
	}

	/**
	 * Returns {@code content} with the leading metadata header block removed, so callers building the
	 * SQL/BSQL body of an entry (e.g. {@code LIB RUN}) never see the {@code -- @key: value} lines as
	 * part of the executable content - important because folding them, unstripped, into a single
	 * space-joined query line (as {@code LIB RUN} already did before this feature) would turn the first
	 * {@code --} into a SQL line comment that silently swallows everything joined after it.
	 *
	 * <p>The header block is the leading contiguous run of comment-only/blank lines, exactly the same
	 * boundary {@link #extractHeaderLines(String)} uses - see that method's javadoc for the exact rule
	 * and the scope decision around directives declared later in the document.
	 */
	public static String stripHeader(String content) {
		if (content == null) {
			return null;
		}
		List<String> lines = splitLines(content);
		int bodyStart = firstBodyLineIndex(content, lines);
		StringBuilder remainder = new StringBuilder();
		for (int i = bodyStart; i < lines.size(); i++) {
			remainder.append(lines.get(i)).append("\n");
		}
		return remainder.toString();
	}

	/**
	 * The leading contiguous run of comment-only/blank lines of {@code content}, in original order,
	 * exactly as written - <b>every</b> such line, not only the ones that happen to declare a
	 * {@code @key} directive, so an ordinary explanatory comment sitting among/before the directives
	 * (spec section 3.6/1.2) is preserved rather than silently dropped. The boundary is the first line
	 * containing anything other than whitespace or a full comment (line or block) - computed via
	 * {@link SqlTokenizer}, so a fake {@code @instance} inside a string literal or past a real SQL
	 * statement never extends the header, and an ordinary (non-{@code @}) comment never prematurely
	 * ends it either. Used by the Script Library's metadata round-trip preservation model ({@code
	 * ScriptMetadataHeader}, SPRINT 0917-01), which patches only the raw line(s) for a known field
	 * being edited through the GUI and leaves every other original line - including comments, blank
	 * lines and unknown/future keys - byte-identical.
	 */
	public static List<String> extractHeaderLines(String content) {
		if (content == null) {
			return new ArrayList<>();
		}
		List<String> lines = splitLines(content);
		int bodyStart = firstBodyLineIndex(content, lines);
		return new ArrayList<>(lines.subList(0, Math.min(bodyStart, lines.size())));
	}

	/**
	 * The 0-based index, within {@code lines} (the same physical lines {@code content} splits into),
	 * of the first line containing real (non-whitespace, non-comment) content - {@code lines.size()}
	 * if the whole document is comments/blank. The first token {@link SqlTokenizer} produces that
	 * isn't {@link SqlToken#isInsignificant()} (whitespace or either comment kind) marks it; nothing
	 * "real" can have appeared on an earlier line, by construction, since tokens are produced in
	 * document order. Falls back to a plain per-line {@code "--"}/blank check if the content can't be
	 * safely tokenized at all (mirrors {@link #parse(String)}'s own fallback).
	 */
	private static int firstBodyLineIndex(String content, List<String> lines) {
		try {
			for (SqlToken token : SqlTokenizer.tokenize(content)) {
				if (token.isInsignificant()) {
					continue;
				}
				return lineIndexOf(content, token.getStart());
			}
			return lines.size();
		} catch (SqlLexException e) {
			for (int i = 0; i < lines.size(); i++) {
				String trimmed = lines.get(i).trim();
				if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
					return i;
				}
			}
			return lines.size();
		}
	}

	/** The 0-based line number containing character offset {@code offset} into {@code content} (counts {@code '\n'} occurrences strictly before it). */
	private static int lineIndexOf(String content, int offset) {
		int line = 0;
		int limit = Math.min(offset, content.length());
		for (int i = 0; i < limit; i++) {
			if (content.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	private static List<String> splitLines(String content) {
		List<String> lines = new ArrayList<>();
		if (content == null) {
			return lines;
		}
		try (BufferedReader br = new BufferedReader(new StringReader(content))) {
			String line;
			while ((line = br.readLine()) != null) {
				lines.add(line);
			}
		} catch (IOException ignored) {
			// StringReader never actually throws; keeps the try-with-resources tidy.
		}
		return lines;
	}

	/**
	 * The inner physical lines of a {@code /* ... *}{@code /} block comment's raw token text, with the
	 * opening {@code /*} and closing {@code *}{@code /} delimiters stripped - each returned line is
	 * then analyzed by {@link #analyzeDirectiveLine(String)} exactly like a line comment's text would
	 * be, so a leading {@code *} continuation marker (the common {@code /** ... * @key value ... *}{@code /}
	 * Javadoc-like style) or a bare {@code @key value} line (no marker at all) both work.
	 */
	private static List<String> blockCommentInnerLines(String blockCommentText) {
		String body = blockCommentText;
		if (body.startsWith("/*")) {
			body = body.substring(2);
		}
		if (body.endsWith("*/")) {
			body = body.substring(0, body.length() - 2);
		}
		List<String> result = new ArrayList<>();
		for (String line : body.split("\r\n|\r|\n", -1)) {
			result.add(line);
		}
		return result;
	}

	/**
	 * Recognizes {@code line} as a single {@code @key ...} directive, regardless of which valid
	 * comment style it came from: a {@code --} line comment, a plain or {@code *}-continuation block
	 * comment line, or (fallback only) a bare line. Returns {@code null} if {@code line} carries no
	 * recognizable directive at all (an ordinary comment, blank line, or real SQL text) - callers must
	 * not treat a {@code null} result as an error, only as "not a directive."
	 *
	 * <p>Both {@code @key value} (space-separated) and {@code @key: value} (colon-separated) are
	 * recognized on equal footing; {@link DirectiveLine#hasSeparator()} distinguishes a genuinely bare
	 * {@code @key} (no separator, no value at all) from a deliberately empty declaration like {@code @description:} (colon present,
	 * value empty - the shape a brand-new, not-yet-filled-in Script starts with).
	 */
	public static DirectiveLine analyzeDirectiveLine(String line) {
		if (line == null) {
			return null;
		}
		int atIdx = atMarkerIndex(line);
		if (atIdx < 0) {
			return null;
		}
		String afterAt = line.substring(atIdx + 1);
		int sepIdx = -1;
		for (int i = 0; i < afterAt.length(); i++) {
			char c = afterAt.charAt(i);
			if (Character.isWhitespace(c) || c == ':') {
				sepIdx = i;
				break;
			}
		}
		String key;
		String value;
		boolean hasSeparator;
		if (sepIdx < 0) {
			key = afterAt.trim();
			value = "";
			hasSeparator = false;
		} else {
			key = afterAt.substring(0, sepIdx).trim();
			String rest = afterAt.substring(sepIdx).trim();
			if (rest.startsWith(":")) {
				rest = rest.substring(1).trim();
			}
			value = rest;
			hasSeparator = true;
		}
		if (key.isEmpty()) {
			return null;
		}
		return new DirectiveLine(key.toLowerCase(), value, hasSeparator, atIdx);
	}

	/**
	 * The character offset of the directive-introducing {@code '@'} in {@code line}, or {@code -1} if
	 * {@code line} doesn't qualify at all: after skipping leading whitespace, the {@code '@'} must
	 * appear either immediately, right after a {@code "--"} line-comment marker (plus its own
	 * whitespace), or right after a single {@code '*'} block-comment continuation marker (plus its own
	 * whitespace, and not the two-character {@code *}{@code /} closing delimiter). A bare {@code '@'} deeper
	 * inside a line - e.g. the one inside {@code SELECT '@instance WRONG';} - never qualifies, since
	 * real content precedes it on the same line.
	 */
	private static int atMarkerIndex(String line) {
		int i = 0;
		int n = line.length();
		while (i < n && Character.isWhitespace(line.charAt(i))) {
			i++;
		}
		if (i + 1 < n && line.charAt(i) == '-' && line.charAt(i + 1) == '-') {
			i += 2;
			while (i < n && Character.isWhitespace(line.charAt(i))) {
				i++;
			}
		} else if (i < n && line.charAt(i) == '*' && !(i + 1 < n && line.charAt(i + 1) == '/')) {
			i += 1;
			while (i < n && Character.isWhitespace(line.charAt(i))) {
				i++;
			}
		}
		if (i < n && line.charAt(i) == '@') {
			return i;
		}
		return -1;
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

	public static final String STATUS_DRAFT = "draft";
	public static final String STATUS_STABLE = "stable";
	public static final String STATUS_DEPRECATED = "deprecated";

	/**
	 * The one authoritative {@code @status} vocabulary (as documented in {@code library_and_scripts.md}):
	 * the BroadSQL Editor's Status list and its Save-time integrity check both read this, never a second
	 * GUI-only list. {@code @status} stays purely informational for {@code LIST}/{@code FIND}, and a
	 * missing/blank status is also legal ("not specified").
	 */
	public static final List<String> VALID_STATUSES = List.of(STATUS_DRAFT, STATUS_STABLE, STATUS_DEPRECATED);

	/** The status a brand-new asset starts with. */
	public static final String DEFAULT_NEW_STATUS = STATUS_DRAFT;

	/** Case-insensitive membership in {@link #VALID_STATUSES}; a blank value is <em>not</em> a valid status (callers treat blank as "not specified" separately). */
	public static boolean isValidStatus(String status) {
		if (status == null) {
			return false;
		}
		String trimmed = status.trim();
		for (String valid : VALID_STATUSES) {
			if (valid.equalsIgnoreCase(trimmed)) {
				return true;
			}
		}
		return false;
	}

	public String getTagsDisplayValue() {
		return String.join(",", tags);
	}

	/**
	 * SPRINT 0110A: every name of every {@code -- @params:} directive, as written and in declaration order,
	 * duplicates and invalid names included (what {@code LIB LINT} reports on).
	 */
	public List<String> getDeclaredParams() {
		return declaredParams;
	}

	/**
	 * SPRINT 0110A: the arguments each call of this Script must provide explicitly (spec section 17.6): the declared
	 * names in declaration order, duplicates (case-insensitive) removed.
	 */
	public List<String> getParams() {
		List<String> params = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (String name : declaredParams) {
			if (seen.add(name.toUpperCase(java.util.Locale.ROOT))) {
				params.add(name);
			}
		}
		return params;
	}

	/** SPRINT 0110A: {@code Parameters: a, b} or {@code Parameters: none declared} ({@code LIB SHOW}, the Editor). */
	public String getParamsDisplayValue() {
		List<String> params = getParams();
		return params.isEmpty() ? "none declared" : String.join(", ", params);
	}
}
