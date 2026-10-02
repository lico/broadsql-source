package com.upandcoding.broadsql.controller.shell.style;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;

/**
 * SPRINT 2409K: the one place BroadSQL turns a {@link StyleRole} into terminal output. Immutable: the
 * effective state (theme, whether styling is on, the terminal whose capabilities shape the escape
 * sequences) is decided once by {@link #resolve} and published through {@link TerminalStyleHolder}.
 *
 * <p>When styling is off (theme {@code none}, {@code color=OFF}, or {@code color=AUTO} without a color
 * terminal) every method returns its input unchanged, so output is byte-for-byte what BroadSQL printed
 * before theming existed; in particular no escape sequence can reach a redirected output, a log file or a
 * test capture.
 */
public final class TerminalStyle {

	/** No styling at all: the state before {@link #resolve} runs, and with {@code activatejline=OFF} under {@code color=AUTO}. */
	public static final TerminalStyle PLAIN = new TerminalStyle(Theme.named(Theme.NONE), false, null);

	private final Theme theme;
	private final boolean enabled;
	private final Terminal terminal;

	private TerminalStyle(Theme theme, boolean enabled, Terminal terminal) {
		this.theme = theme;
		this.enabled = enabled;
		this.terminal = terminal;
	}

	/**
	 * Decides whether styling is on: never for theme {@code none} or {@code color=OFF}; always for
	 * {@code color=ON}; for {@code color=AUTO}, only when {@code terminal} reports color support
	 * ({@link #supportsColor}). A {@code null} terminal means BroadSQL is not running on JLine.
	 */
	public static TerminalStyle resolve(ColorMode mode, Theme theme, Terminal terminal) {
		Theme effectiveTheme = theme == null ? Theme.named(Theme.DEFAULT) : theme;
		ColorMode effectiveMode = mode == null ? ColorMode.AUTO : mode;
		boolean on = !effectiveTheme.isLegacy()
				&& (effectiveMode == ColorMode.ON || (effectiveMode == ColorMode.AUTO && supportsColor(terminal)));
		return new TerminalStyle(effectiveTheme, on, terminal);
	}

	/**
	 * {@code true} when {@code terminal} is a real terminal with at least 8 colors: not {@code null}, not
	 * JLine's plain {@code dumb} terminal (what JLine builds when output is redirected or no console is
	 * attached), and reporting a {@code max_colors} capability of 8 or more. Based on what the terminal
	 * reports, never on the operating system.
	 */
	public static boolean supportsColor(Terminal terminal) {
		if (terminal == null || Terminal.TYPE_DUMB.equals(terminal.getType())) {
			return false;
		}
		try {
			Integer colors = terminal.getNumericCapability(InfoCmp.Capability.max_colors);
			return colors != null && colors >= 8;
		} catch (RuntimeException e) {
			return false;
		}
	}

	public boolean isEnabled() {
		return enabled;
	}

	public Theme getTheme() {
		return theme;
	}

	/** {@code text} styled for {@code role}, as escape sequences for this terminal; {@code text} itself when styling is off or the role is unstyled. */
	public String render(StyleRole role, String text) {
		if (!enabled || text == null || text.isEmpty()) {
			return text;
		}
		AttributedStyle style = theme.style(role);
		if (AttributedStyle.DEFAULT.equals(style)) {
			return text;
		}
		return new AttributedString(text, style).toAnsi(terminal);
	}

	/** The role's JLine style, or {@link AttributedStyle#DEFAULT} when styling is off (for JLine's own rendering, e.g. the highlighter). */
	public AttributedStyle attributes(StyleRole role) {
		return enabled ? theme.style(role) : AttributedStyle.DEFAULT;
	}

	/** {@code true} when the syntax highlighter and completion-menu styles should be installed. */
	public boolean highlightsInput() {
		return enabled && !theme.isLegacy();
	}
}
