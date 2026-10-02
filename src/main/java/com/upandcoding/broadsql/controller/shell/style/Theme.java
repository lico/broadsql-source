package com.upandcoding.broadsql.controller.shell.style;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jline.utils.AttributedStyle;
import org.jline.utils.StyleResolver;

/**
 * SPRINT 2409K: how each {@link StyleRole} looks. A role's style is a JLine style specification
 * ({@code "fg:bright-red,bold"}, see {@link StyleResolver}): the same text serves for rendering through
 * {@link TerminalStyle} and for JLine's own completion-menu variables. Only the 16 standard ANSI colors
 * and plain attributes are used, so every theme renders on the Windows console, Windows Terminal and
 * common Unix terminals alike. A role a theme does not list is not styled.
 *
 * <p>The built-in themes are deliberately few: {@code default-dark} (the default, for dark backgrounds),
 * {@code default-light} (for light backgrounds), {@code classic} (the traditional red/yellow/green
 * message colors and bold keywords, readable on any background), {@code high-contrast-dark},
 * {@code high-contrast-light}, {@code mono} (attributes only, no color) and {@code none}.
 *
 * <p>{@code none} is the compatibility mode, not an empty palette: with it BroadSQL keeps its pre-theme
 * appearance exactly (no syntax highlighting installed, JLine's own completion-menu colors untouched,
 * plain prompt and messages). {@link #isLegacy()} tells callers to install nothing at all.
 */
public final class Theme {

	public static final String DEFAULT_DARK = "default-dark";
	public static final String DEFAULT_LIGHT = "default-light";
	public static final String CLASSIC = "classic";
	public static final String HIGH_CONTRAST_DARK = "high-contrast-dark";
	public static final String HIGH_CONTRAST_LIGHT = "high-contrast-light";
	public static final String MONO = "mono";
	public static final String NONE = "none";

	/** The theme used when {@code theme} is absent or names no built-in theme. */
	public static final String DEFAULT = DEFAULT_DARK;

	private static final Map<String, Theme> BUILT_IN = buildBuiltIns();

	private final String name;
	private final Map<StyleRole, String> specs;
	private final Map<StyleRole, AttributedStyle> styles = new EnumMap<>(StyleRole.class);

	private Theme(String name, Map<StyleRole, String> specs) {
		this.name = name;
		this.specs = Collections.unmodifiableMap(specs);
		StyleResolver resolver = new StyleResolver(key -> null);
		for (Map.Entry<StyleRole, String> entry : specs.entrySet()) {
			styles.put(entry.getKey(), resolver.resolve(entry.getValue()));
		}
	}

	public String getName() {
		return name;
	}

	/** {@code true} for {@code none}: nothing introduced by theming may be installed or emitted. */
	public boolean isLegacy() {
		return NONE.equals(name);
	}

	/** The role's style; {@link AttributedStyle#DEFAULT} (no styling) when the theme does not style it. */
	public AttributedStyle style(StyleRole role) {
		return styles.getOrDefault(role, AttributedStyle.DEFAULT);
	}

	/** The role's JLine style specification, or {@code null} when the theme does not style it. */
	public String spec(StyleRole role) {
		return specs.get(role);
	}

	/** Every built-in theme name, in documentation order. */
	public static List<String> names() {
		return List.copyOf(BUILT_IN.keySet());
	}

	/** The built-in theme named {@code name} (case-insensitive, trimmed), or {@code null} if there is none. */
	public static Theme named(String name) {
		return name == null ? null : BUILT_IN.get(name.trim().toLowerCase(Locale.ROOT));
	}

	private static Map<String, Theme> buildBuiltIns() {
		Map<String, Theme> themes = new LinkedHashMap<>();
		themes.put(DEFAULT_DARK, new Theme(DEFAULT_DARK, roles(
				"fg:bright-red,bold", "fg:bright-yellow", "fg:bright-cyan", "fg:bright-green",
				"bold", "fg:bright-cyan,bold", "fg:bright-magenta", "fg:bright-white,bg:red,bold",
				"fg:bright-blue,bold", "fg:bright-green", "fg:bright-black,italic",
				"fg:bright-yellow,bold", "fg:bright-white", "fg:bright-cyan")));
		themes.put(DEFAULT_LIGHT, new Theme(DEFAULT_LIGHT, roles(
				"fg:red,bold", "fg:magenta", "fg:blue", "fg:green",
				"bold", "fg:blue,bold", "fg:magenta", "fg:bright-white,bg:red,bold",
				"fg:blue,bold", "fg:green", "fg:bright-black,italic",
				"fg:magenta,bold", "fg:black", "fg:blue")));
		themes.put(CLASSIC, new Theme(CLASSIC, roles(
				"fg:red", "fg:yellow", null, "fg:green",
				null, "bold", null, "fg:red,bold",
				"bold", null, null,
				"bold", null, null)));
		themes.put(HIGH_CONTRAST_DARK, new Theme(HIGH_CONTRAST_DARK, roles(
				"fg:bright-white,bg:red,bold", "fg:black,bg:bright-yellow,bold", "fg:bright-cyan,bold", "fg:bright-green,bold",
				"fg:bright-white,bold", "fg:bright-yellow,bold,underline", "fg:bright-white,bold", "fg:bright-white,bg:red,bold,underline",
				"fg:bright-cyan,bold", "fg:bright-green,bold", "fg:bright-white,italic",
				"fg:bright-yellow,bold,underline", "fg:bright-white,bold", "fg:bright-yellow,bold")));
		themes.put(HIGH_CONTRAST_LIGHT, new Theme(HIGH_CONTRAST_LIGHT, roles(
				"fg:bright-white,bg:red,bold", "fg:black,bg:yellow,bold", "fg:blue,bold", "fg:green,bold",
				"fg:black,bold", "fg:blue,bold,underline", "fg:black,bold", "fg:bright-white,bg:red,bold,underline",
				"fg:blue,bold", "fg:green,bold", "fg:black,italic",
				"fg:magenta,bold,underline", "fg:black,bold", "fg:blue,bold")));
		themes.put(MONO, new Theme(MONO, roles(
				"bold", "bold", null, null,
				"bold", "bold", "underline", "inverse,bold",
				"bold", null, "faint",
				"bold,underline", null, "bold")));
		themes.put(NONE, new Theme(NONE, new EnumMap<>(StyleRole.class)));
		return Collections.unmodifiableMap(themes);
	}

	/** The specs in {@link StyleRole} declaration order; {@code null} leaves a role unstyled. */
	private static Map<StyleRole, String> roles(String... specs) {
		StyleRole[] roles = StyleRole.values();
		if (specs.length != roles.length) {
			throw new IllegalStateException("a theme lists " + specs.length + " styles for " + roles.length + " roles");
		}
		Map<StyleRole, String> map = new EnumMap<>(StyleRole.class);
		for (int i = 0; i < roles.length; i++) {
			if (specs[i] != null) {
				map.put(roles[i], specs[i]);
			}
		}
		return map;
	}
}
