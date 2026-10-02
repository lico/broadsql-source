package com.upandcoding.broadsql.dao.api.http;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;

/** SPRINT XT02A (URL-Native API Execution), section 19 - enterprise proxy configuration. */
class TestApiProxyConfig {

	private ConsoleSettings settingsWith(String mode, String type, String host, String port, String nonProxy, String user, String pass) {
		ConsoleSettings settings = new ConsoleSettings();
		settings.setApiProxyMode(mode);
		settings.setApiProxyType(type);
		settings.setApiProxyHost(host);
		settings.setApiProxyPort(port);
		settings.setApiProxyNonProxyHosts(nonProxy);
		settings.setApiProxyUsername(user);
		settings.setApiProxyPassword(pass);
		return settings;
	}

	@Test
	void defaultsToNoneWhenModeIsBlank() throws BroadSQLException {
		ApiProxyConfig config = ApiProxyConfig.fromSettings(settingsWith(null, null, null, null, null, null, null));
		Assertions.assertTrue(config.isNone());
		Assertions.assertNull(config.toProxySelectorOrNull());
		Assertions.assertNull(config.toAuthenticatorOrNull());
	}

	@Test
	void unrecognizedModeFallsBackToNone() throws BroadSQLException {
		ApiProxyConfig config = ApiProxyConfig.fromSettings(settingsWith("BOGUS", null, null, null, null, null, null));
		Assertions.assertTrue(config.isNone());
	}

	@Test
	void systemModeNeverProducesAnExplicitSelector() throws BroadSQLException {
		ApiProxyConfig config = ApiProxyConfig.fromSettings(settingsWith("SYSTEM", null, null, null, null, null, null));
		Assertions.assertTrue(config.isSystem());
		Assertions.assertNull(config.toProxySelectorOrNull(), "SYSTEM must never produce its own selector - see ApiHttpTransport javadoc");
	}

	@Test
	void manualModeRoutesThroughTheConfiguredHostAndPort() throws BroadSQLException {
		ApiProxyConfig config = ApiProxyConfig.fromSettings(settingsWith("MANUAL", "HTTP", "proxy.corp.example", "8080", null, null, null));
		ProxySelector selector = config.toProxySelectorOrNull();
		Assertions.assertNotNull(selector);
		Proxy proxy = selector.select(URI.create("https://api.example.com/x")).get(0);
		Assertions.assertEquals(Proxy.Type.HTTP, proxy.type());
		Assertions.assertEquals("proxy.corp.example", ((java.net.InetSocketAddress) proxy.address()).getHostString());
		Assertions.assertEquals(8080, ((java.net.InetSocketAddress) proxy.address()).getPort());
	}

	@Test
	void manualModeWithoutHostOrPortIsTreatedAsUnconfigured() throws BroadSQLException {
		ApiProxyConfig config = ApiProxyConfig.fromSettings(settingsWith("MANUAL", null, null, null, null, null, null));
		Assertions.assertNull(config.toProxySelectorOrNull());
	}

	@Test
	void nonProxyHostsBypassTheManualProxy() throws BroadSQLException {
		ApiProxyConfig config = ApiProxyConfig.fromSettings(
				settingsWith("MANUAL", "HTTP", "proxy.corp.example", "8080", "localhost|*.corp.internal", null, null));
		ProxySelector selector = config.toProxySelectorOrNull();

		Assertions.assertEquals(Proxy.NO_PROXY, selector.select(URI.create("http://localhost/x")).get(0));
		Assertions.assertEquals(Proxy.NO_PROXY, selector.select(URI.create("http://svc.corp.internal/x")).get(0));
		Assertions.assertNotEquals(Proxy.NO_PROXY, selector.select(URI.create("https://api.example.com/x")).get(0));
	}

	@Test
	void manualModeWithCredentialsProducesAProxyOnlyAuthenticator() throws BroadSQLException {
		ApiProxyConfig config = ApiProxyConfig.fromSettings(settingsWith("MANUAL", "HTTP", "proxy.corp.example", "8080", null, "svc-user", "svc-pass"));
		Assertions.assertNotNull(config.toAuthenticatorOrNull());
	}

	@Test
	void manualModeWithoutCredentialsProducesNoAuthenticator() throws BroadSQLException {
		ApiProxyConfig config = ApiProxyConfig.fromSettings(settingsWith("MANUAL", "HTTP", "proxy.corp.example", "8080", null, null, null));
		Assertions.assertNull(config.toAuthenticatorOrNull());
	}

	@Test
	void credentialResolvesAnEnvironmentVariableReference() throws BroadSQLException {
		ConsoleSettings settings = settingsWith("MANUAL", "HTTP", "proxy.corp.example", "8080", null, "${ENV:PATH}", null);
		org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("PATH") != null);
		ApiProxyConfig config = ApiProxyConfig.fromSettings(settings);
		Assertions.assertTrue(config.hasCredentials());
	}

	@Test
	void undefinedCredentialEnvironmentVariableFailsExplicitly() {
		ConsoleSettings settings = settingsWith("MANUAL", "HTTP", "proxy.corp.example", "8080", null, "${ENV:BROADSQL_XT02A_DOES_NOT_EXIST}", null);
		Assertions.assertThrows(BroadSQLException.class, () -> ApiProxyConfig.fromSettings(settings));
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02A corrective pass (16/09/2026): SYSTEM mode's actual scoping - a real, JVM-global
	// system property, unlike NONE/MANUAL - see ApiProxyConfig's own corrected javadoc.
	// ------------------------------------------------------------------------------------------

	@Test
	void systemModeSetsTheJvmGlobalUseSystemProxiesProperty() {
		String original = System.getProperty("java.net.useSystemProxies");
		System.clearProperty("java.net.useSystemProxies");
		try {
			ApiProxyConfig.applySystemPropertyIfNeeded(settingsWith("SYSTEM", null, null, null, null, null, null));
			Assertions.assertEquals("true", System.getProperty("java.net.useSystemProxies"),
					"SYSTEM mode's actual effect is this JVM-global system property - it is not, and cannot be, scoped to ApiHttpTransport alone");
		} finally {
			if (original == null) {
				System.clearProperty("java.net.useSystemProxies");
			} else {
				System.setProperty("java.net.useSystemProxies", original);
			}
		}
	}

	@Test
	void noneAndManualModeNeverTouchTheJvmGlobalProperty() {
		String original = System.getProperty("java.net.useSystemProxies");
		System.clearProperty("java.net.useSystemProxies");
		try {
			ApiProxyConfig.applySystemPropertyIfNeeded(settingsWith("NONE", null, null, null, null, null, null));
			Assertions.assertNull(System.getProperty("java.net.useSystemProxies"));

			ApiProxyConfig.applySystemPropertyIfNeeded(settingsWith("MANUAL", "HTTP", "proxy.corp.example", "8080", null, null, null));
			Assertions.assertNull(System.getProperty("java.net.useSystemProxies"),
					"MANUAL is genuinely transport-scoped - it must never touch this JVM-global property");
		} finally {
			if (original == null) {
				System.clearProperty("java.net.useSystemProxies");
			} else {
				System.setProperty("java.net.useSystemProxies", original);
			}
		}
	}

	@Test
	void apiHttpTransportIsTheOnlyJavaNetHttpClientUserInTheApplication() throws java.io.IOException {
		// Regression guard for the scoping claim in ApiProxyConfig's javadoc: SYSTEM mode's JVM-global
		// property has no observable effect on anything else in this codebase ONLY because no other
		// java.net.http.HttpClient exists yet. If this test ever fails, that fact has changed and the
		// javadoc/closure report's "no effect on other networking today" claim needs re-verifying, not
		// just this test updating.
		java.nio.file.Path srcRoot = java.nio.file.Path.of("src", "main", "java");
		org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(srcRoot), "must run with the repo root as the working directory");
		java.util.List<String> offendingFiles = new java.util.ArrayList<>();
		try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(srcRoot)) {
			for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
				String content = java.nio.file.Files.readString(file);
				boolean usesHttpClient = content.contains("HttpClient.newBuilder(") || content.contains("HttpClient.newHttpClient(");
				boolean isApiHttpTransport = file.getFileName().toString().equals("ApiHttpTransport.java");
				if (usesHttpClient && !isApiHttpTransport) {
					offendingFiles.add(file.toString());
				}
			}
		}
		Assertions.assertTrue(offendingFiles.isEmpty(),
				"found another java.net.http.HttpClient user besides ApiHttpTransport: " + offendingFiles
						+ " - SYSTEM proxy mode's JVM-global property now affects this code too; update ApiProxyConfig's javadoc/closure report accordingly");
	}
}
