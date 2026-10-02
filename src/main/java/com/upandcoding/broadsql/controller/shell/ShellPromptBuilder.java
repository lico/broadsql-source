package com.upandcoding.broadsql.controller.shell;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.shell.style.StyleRole;
import com.upandcoding.broadsql.controller.shell.style.TerminalStyle;
import com.upandcoding.broadsql.dao.api.ApiSessionContext;
import com.upandcoding.broadsql.dao.api.ApiSessionContextHolder;

/**
 * Composes the shell prompt from the active SQL platform and the active API session context
 * (SPRINT XT02-7B) - the single place that decides what the prompt looks like, so every call site
 * that used to hard-code {@code platform + "> "} (the {@code CommandInterpreter} constructor,
 * {@code CommandInterpreter.run()}, and {@code CommandConnect}'s SQL success branch) produces the
 * same, always-current combination of both contexts.
 *
 * <p>Reads {@link ApiSessionContextHolder} directly rather than taking it as a parameter, so a caller
 * that only changed the SQL half (or only changed the API half) never needs to know about, or
 * re-fetch, the other half - it simply always gets the current combined prompt.
 *
 * <p>The safety principle behind this class (docs/SPRINT XT02-7B, section 7.5): the prompt must never
 * visually suggest that the SQL connection has disappeared while it remains active, and vice versa -
 * both segments are always shown together whenever both are active.
 */
public final class ShellPromptBuilder {

	private ShellPromptBuilder() {
	}

	/**
	 * @param platform the active SQL platform id (e.g. {@code "$CDF"}), or blank/{@code null} if no SQL
	 *                 connection is active
	 * @return the full prompt text, always ending in {@code "> "}
	 */
	public static String build(String platform) {
		ApiSessionContext apiContext = ApiSessionContextHolder.get();
		String sqlSegment = StringUtils.trimToEmpty(platform);
		if (apiContext == null) {
			return sqlSegment + "> ";
		}
		String apiSegment = "[API " + apiContext.getApiId() + ":" + apiContext.getEnvironmentName() + "]";
		if (sqlSegment.isEmpty()) {
			return apiSegment + "> ";
		}
		return sqlSegment + " " + apiSegment + "> ";
	}

	private static final String API_OPEN = "[API ";
	private static final String PROMPT_END = "> ";

	/**
	 * SPRINT 2409K: {@code prompt} (as {@link #build} makes it) with the theme's prompt roles applied: the
	 * connection id as {@link StyleRole#PROMPT_CONNECTION}, or {@link StyleRole#PROMPT_PRODUCTION} when
	 * {@code production}; the API environment as {@link StyleRole#PROMPT_ENVIRONMENT}; the rest as
	 * {@link StyleRole#PROMPT}. Adds styling only: the visible text is always exactly {@code prompt}, and
	 * with styling off {@code prompt} itself is returned. A prompt not in {@link #build}'s shape is styled
	 * as {@link StyleRole#PROMPT} as a whole.
	 */
	public static String style(String prompt, TerminalStyle style, boolean production) {
		if (prompt == null || style == null || !style.isEnabled()) {
			return prompt;
		}
		if (!prompt.endsWith(PROMPT_END)) {
			return style.render(StyleRole.PROMPT, prompt);
		}
		String body = prompt.substring(0, prompt.length() - PROMPT_END.length());
		StyleRole connectionRole = production ? StyleRole.PROMPT_PRODUCTION : StyleRole.PROMPT_CONNECTION;
		StringBuilder sb = new StringBuilder();
		int api = body.indexOf(API_OPEN);
		if (api < 0 || !body.endsWith("]")) {
			sb.append(style.render(connectionRole, body));
		} else {
			String sql = body.substring(0, api);
			String inner = body.substring(api + API_OPEN.length(), body.length() - 1);
			int colon = inner.lastIndexOf(':');
			String sqlId = sql.trim();
			if (!sqlId.isEmpty()) {
				sb.append(style.render(connectionRole, sqlId)).append(sql.substring(sqlId.length()));
			} else {
				sb.append(sql);
			}
			sb.append(style.render(StyleRole.PROMPT, API_OPEN));
			if (colon < 0) {
				sb.append(style.render(StyleRole.PROMPT, inner));
			} else {
				sb.append(style.render(StyleRole.PROMPT, inner.substring(0, colon + 1)));
				sb.append(style.render(StyleRole.PROMPT_ENVIRONMENT, inner.substring(colon + 1)));
			}
			sb.append(style.render(StyleRole.PROMPT, "]"));
		}
		sb.append(style.render(StyleRole.PROMPT, PROMPT_END));
		return sb.toString();
	}
}
