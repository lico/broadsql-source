package com.upandcoding.broadsql.dao.api.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * Generic HTTP transport built on {@code java.net.http.HttpClient} (Java 21 baseline - no external HTTP
 * dependency needed, per docs/SPRINT XT02 - Universal API Client.md, section 9). Sends whatever
 * {@link ApiHttpRequest} it is given - it has no knowledge of authentication schemes or any specific API,
 * and no method restriction of its own: sub-sprint 3's OAuth2 Client Credentials flow already needed
 * {@code POST} internally before user-endpoint execution ever did, and this class already sent a body
 * that whole time. The only allowlist is {@link com.upandcoding.broadsql.dao.api.execution.ApiExecutionPolicy},
 * a policy layer in front of user-endpoint execution, never a transport-level one (section 13.5) - it
 * restricted execution to GET/HEAD in Release 1 (sub-sprint 4) and was widened to also allow POST/PUT/
 * PATCH/DELETE in SPRINT XT02-8, both times with no change needed here.
 *
 * <p>Never logs request/response headers or bodies - both routinely carry secrets (a resolved
 * Authorization header, an API-key query parameter, a client secret in a token request body) that must
 * never reach a log file. A failure is reported with the target host only, never the full URI (which,
 * for {@code API_KEY_QUERY} authentication, would itself contain the secret) or any header/body content.
 */
public class ApiHttpTransport {

	private final HttpClient client;
	private final boolean manualProxyConfigured;

	/** Reads the process-wide {@link ApiProxyConfigHolder} (SPRINT XT02A, section 19) - the normal constructor every real execution path uses. */
	public ApiHttpTransport() {
		this(ApiProxyConfigHolder.get());
	}

	/**
	 * Explicit-config constructor (SPRINT XT02A, section 19) - lets a test exercise MANUAL/SYSTEM/NONE
	 * proxy behavior in isolation, without touching {@link ApiProxyConfigHolder}'s process-wide state.
	 * Only {@code MANUAL} mode ever calls {@code .proxy(...)}/{@code .authenticator(...)} here -
	 * {@code NONE} and {@code SYSTEM} make no explicit proxy call at all (section 19.6): {@code NONE}
	 * is this project's unchanged pre-XT02A behavior, and {@code SYSTEM}'s effect comes entirely from
	 * {@link ApiProxyConfig#applySystemPropertyIfNeeded} having been called once at startup, not from
	 * anything decided here.
	 */
	public ApiHttpTransport(ApiProxyConfig proxyConfig) {
		HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
		boolean manualProxy = false;
		if (proxyConfig != null && proxyConfig.isManual()) {
			java.net.ProxySelector selector = proxyConfig.toProxySelectorOrNull();
			if (selector != null) {
				builder.proxy(selector);
				manualProxy = true;
			}
			java.net.Authenticator authenticator = proxyConfig.toAuthenticatorOrNull();
			if (authenticator != null) {
				builder.authenticator(authenticator);
			}
		}
		this.manualProxyConfigured = manualProxy;
		this.client = builder.build();
	}

	public ApiHttpResponse send(ApiHttpRequest request) throws BroadSQLException {
		URI uri = request.buildUri();
		HttpRequest.BodyPublisher bodyPublisher = request.getBody() == null
				? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(request.getBody(), StandardCharsets.UTF_8);
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(30))
				.method(request.getMethod(), bodyPublisher);
		for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
			builder.header(header.getKey(), header.getValue());
		}

		long start = System.nanoTime();
		try {
			HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
			long durationMillis = (System.nanoTime() - start) / 1_000_000;
			Map<String, String> headers = new LinkedHashMap<>();
			response.headers().map().forEach((name, values) -> headers.put(name, String.join(", ", values)));
			return new ApiHttpResponse(response.statusCode(), headers, response.body(), durationMillis);
		} catch (IOException e) {
			// A basic proxy-vs-target distinction (section 19.8) - not an exhaustive classification of
			// every DNS/TLS/timeout/proxy-authentication failure shape, which java.net.http's IOException
			// hierarchy does not reliably expose without much deeper, brittle inspection; still never a
			// bare, undifferentiated "API call failed" either way, and credentials are never included.
			String context = manualProxyConfigured ? " (via the configured MANUAL proxy - the failure may be the proxy itself, not the target)" : "";
			throw new BroadSQLException("Unable to reach '" + uri.getHost() + "'" + context + ": " + e.getLocalizedMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BroadSQLException("HTTP request to '" + uri.getHost() + "' was interrupted", e);
		}
	}
}
