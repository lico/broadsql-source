package com.upandcoding.broadsql.dao.api.http;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.dao.api.invocation.EnvVarResolver;

/**
 * Enterprise HTTP/HTTPS proxy configuration for API execution - SPRINT XT02A (URL-Native API
 * Execution), section 19. Three modes, with <b>two genuinely different scoping stories</b> (SPRINT
 * XT02A corrective pass, 16/09/2026 - an earlier version of this javadoc incorrectly claimed all
 * three modes are transport-scoped, which is only true for {@code NONE}/{@code MANUAL}):
 * <ul>
 * <li>{@code NONE} (default, backward-compatible): {@link ApiHttpTransport} makes no {@code .proxy(...)}
 * call at all - identical to this project's pre-XT02A behavior. Transport-scoped: touches nothing
 * outside {@link ApiHttpTransport}.</li>
 * <li>{@code MANUAL}: an explicit host/port/type/bypass {@link ProxySelector} ({@link #toProxySelectorOrNull()})
 * and, if credentials are configured, a proxy-only {@link Authenticator} ({@link #toAuthenticatorOrNull()}),
 * both passed directly to {@link ApiHttpTransport}'s own {@code HttpClient.Builder}. Genuinely
 * transport-scoped: nothing outside {@link ApiHttpTransport} is touched, ever.</li>
 * <li>{@code SYSTEM}: <b>not transport-scoped - a real, JVM-wide setting</b>. {@link ApiHttpTransport}
 * itself makes no explicit {@code .proxy(...)} call (identical code path to {@code NONE}); the actual
 * effect comes entirely from {@link #applySystemPropertyIfNeeded} setting the JVM system property
 * {@code java.net.useSystemProxies=true} once, at application startup (section 19.2) - and that
 * property is consulted by the JDK's <i>default</i> {@link ProxySelector}, which is shared by every
 * piece of {@code java.net}/{@code java.net.http} networking in the process, not something this class
 * can scope to one caller. The JDK provides no supported way to ask "give me real OS proxy discovery,
 * but only for this one {@code HttpClient}" - {@code java.net.useSystemProxies} is inherently global by
 * the JDK's own design. In this codebase specifically, {@link ApiHttpTransport} is - as of this sprint -
 * the only {@code java.net.http.HttpClient} anywhere in {@code src/main/java} (verified by a
 * repository-wide search, SPRINT XT02A corrective pass), and BroadSQL's SQL/JDBC connections use raw
 * sockets that never consult {@link ProxySelector} at all, so {@code SYSTEM} mode has no observable
 * effect on anything else <i>today</i>. That is a property of what networking code exists today, not
 * an architectural guarantee - any future BroadSQL feature that opens its own {@code HttpClient}/
 * {@code HttpURLConnection} would also pick up {@code SYSTEM} mode's system-proxy behavior, whether or
 * not that is desired for that feature.</li>
 * </ul>
 */
public final class ApiProxyConfig {

	public static final String MODE_NONE = "NONE";
	public static final String MODE_SYSTEM = "SYSTEM";
	public static final String MODE_MANUAL = "MANUAL";

	private static final ApiProxyConfig NONE = new ApiProxyConfig(MODE_NONE, "HTTP", null, null, List.of(), null, null);

	private final String mode;
	private final String type;
	private final String host;
	private final Integer port;
	private final List<Pattern> nonProxyHostPatterns;
	private final String username;
	private final String password;

	private ApiProxyConfig(String mode, String type, String host, Integer port, List<Pattern> nonProxyHostPatterns, String username, String password) {
		this.mode = mode;
		this.type = type;
		this.host = host;
		this.port = port;
		this.nonProxyHostPatterns = nonProxyHostPatterns;
		this.username = username;
		this.password = password;
	}

	/** The always-safe, no-proxy default - used before {@link ApiProxyConfigHolder} is ever populated (e.g. in tests that never run {@code BroadSQL.main}). */
	public static ApiProxyConfig none() {
		return NONE;
	}

	public static ApiProxyConfig fromSettings(ConsoleSettings settings) throws BroadSQLException {
		String mode = normalizeMode(settings.getApiProxyMode());
		String type = StringUtils.isBlank(settings.getApiProxyType()) ? "HTTP" : settings.getApiProxyType().trim().toUpperCase();
		String host = StringUtils.trimToNull(settings.getApiProxyHost());
		Integer port = parsePort(settings.getApiProxyPort());
		List<Pattern> nonProxy = parseNonProxyHosts(settings.getApiProxyNonProxyHosts());
		String username = resolveCredential(settings.getApiProxyUsername());
		String password = resolveCredential(settings.getApiProxyPassword());
		return new ApiProxyConfig(mode, type, host, port, nonProxy, username, password);
	}

	private static String resolveCredential(String raw) throws BroadSQLException {
		return StringUtils.isBlank(raw) ? null : EnvVarResolver.resolve(raw);
	}

	private static String normalizeMode(String raw) {
		if (StringUtils.isBlank(raw)) {
			return MODE_NONE;
		}
		String upper = raw.trim().toUpperCase();
		return (MODE_SYSTEM.equals(upper) || MODE_MANUAL.equals(upper)) ? upper : MODE_NONE;
	}

	private static Integer parsePort(String raw) {
		if (StringUtils.isBlank(raw)) {
			return null;
		}
		try {
			return Integer.valueOf(raw.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Java-style pipe-separated bypass patterns (section 19.5) - {@code *} is the only wildcard recognized. */
	private static List<Pattern> parseNonProxyHosts(String raw) {
		List<Pattern> patterns = new ArrayList<>();
		if (StringUtils.isBlank(raw)) {
			return patterns;
		}
		for (String token : raw.split("\\|")) {
			String trimmed = token.trim();
			if (!trimmed.isEmpty()) {
				String regex = Pattern.quote(trimmed).replace("*", "\\E.*\\Q");
				patterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
			}
		}
		return patterns;
	}

	public String getMode() {
		return mode;
	}

	public boolean isNone() {
		return MODE_NONE.equals(mode);
	}

	public boolean isSystem() {
		return MODE_SYSTEM.equals(mode);
	}

	public boolean isManual() {
		return MODE_MANUAL.equals(mode);
	}

	public boolean hasCredentials() {
		return StringUtils.isNotBlank(username);
	}

	private boolean isBypassed(String host) {
		if (host == null) {
			return false;
		}
		for (Pattern p : nonProxyHostPatterns) {
			if (p.matcher(host).matches()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code null} unless this is a valid MANUAL configuration (host and port both present) - a
	 * MANUAL mode missing either is treated as unconfigured, matching {@code NONE} rather than
	 * failing every request; {@link ApiHttpTransport} only calls {@code .proxy(...)} when this
	 * returns non-null.
	 */
	public ProxySelector toProxySelectorOrNull() {
		if (!isManual() || host == null || port == null) {
			return null;
		}
		InetSocketAddress address = InetSocketAddress.createUnresolved(host, port);
		Proxy.Type proxyType = "SOCKS".equalsIgnoreCase(type) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
		Proxy proxy = new Proxy(proxyType, address);
		return new ProxySelector() {
			@Override
			public List<Proxy> select(URI uri) {
				return isBypassed(uri.getHost()) ? List.of(Proxy.NO_PROXY) : List.of(proxy);
			}

			@Override
			public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
				// Nothing to record here - ApiHttpTransport distinguishes a proxy failure from a target
				// failure for the user-facing error message (section 19.8), not this selector.
			}
		};
	}

	/** {@code null} unless MANUAL mode has credentials configured - {@link ApiHttpTransport} only calls {@code .authenticator(...)} when this returns non-null. */
	public Authenticator toAuthenticatorOrNull() {
		if (!isManual() || !hasCredentials()) {
			return null;
		}
		char[] passwordChars = password == null ? new char[0] : password.toCharArray();
		return new Authenticator() {
			@Override
			protected PasswordAuthentication getPasswordAuthentication() {
				if (getRequestorType() != RequestorType.PROXY) {
					return null;
				}
				return new PasswordAuthentication(username, passwordChars);
			}
		};
	}

	/**
	 * Sets the JVM-global system property {@code java.net.useSystemProxies=true} for {@code SYSTEM}
	 * mode - must be called once, as early as possible at application startup (section 19.2), before
	 * any HTTP stack usage, since the JVM's default {@link ProxySelector} can cache the underlying OS
	 * lookup on first use. <b>This is not scoped to BroadSQL's API transport</b> - see this class's own
	 * javadoc for exactly what that means in practice. A no-op for {@code NONE}/{@code MANUAL} -
	 * {@code MANUAL} is genuinely scoped entirely to this class's own explicit
	 * {@link #toProxySelectorOrNull()} (section 19.7), needing no JVM-global property at all.
	 */
	public static void applySystemPropertyIfNeeded(ConsoleSettings settings) {
		if (MODE_SYSTEM.equals(normalizeMode(settings.getApiProxyMode()))) {
			System.setProperty("java.net.useSystemProxies", "true");
		}
	}
}
