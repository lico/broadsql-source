package com.upandcoding.broadsql.dao.api.auth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A minimal local HTTP server for SPRINT XT02 authentication tests - no public internet dependency, per
 * docs/SPRINT XT02 - Universal API Client.md's testing requirements. Built on
 * {@code com.sun.net.httpserver.HttpServer} (part of the JDK, no new test dependency) rather than a mock
 * library, since what these tests need is a real socket a real {@code java.net.http.HttpClient} request
 * actually reaches - not a mocked call. Records every request received (method, URI, headers, body) per
 * path, so a test can assert exactly what the authentication layer sent.
 */
public class TestLocalHttpServer implements AutoCloseable {

	private final HttpServer server;
	private final Map<String, RouteResponse> routes = new ConcurrentHashMap<>();
	private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());

	public TestLocalHttpServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	public void setRoute(String path, int status, String body) {
		routes.put(path, new RouteResponse(status, body));
	}

	private void handle(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRequestHeaders(), body));
		RouteResponse route = routes.getOrDefault(exchange.getRequestURI().getPath(), new RouteResponse(200, "{}"));
		byte[] responseBytes = route.body.getBytes(StandardCharsets.UTF_8);
		boolean isHead = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		// A HEAD response must declare its content length without an actual body (RFC 9110 §9.3.2) -
		// com.sun.net.httpserver logs a warning if sendResponseHeaders is given a body length for HEAD.
		exchange.sendResponseHeaders(route.status, isHead ? -1 : responseBytes.length);
		if (isHead) {
			exchange.close();
			return;
		}
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(responseBytes);
		}
	}

	public List<RecordedRequest> requestsTo(String path) {
		synchronized (requests) {
			return requests.stream().filter(r -> path.equals(r.uri.getPath())).collect(Collectors.toList());
		}
	}

	public int countRequestsTo(String path) {
		return requestsTo(path).size();
	}

	public RecordedRequest lastRequestTo(String path) {
		List<RecordedRequest> matches = requestsTo(path);
		return matches.isEmpty() ? null : matches.get(matches.size() - 1);
	}

	@Override
	public void close() {
		server.stop(0);
	}

	public static final class RecordedRequest {
		public final String method;
		public final URI uri;
		public final Headers headers;
		public final String body;

		RecordedRequest(String method, URI uri, Headers headers, String body) {
			this.method = method;
			this.uri = uri;
			this.headers = headers;
			this.body = body;
		}
	}

	private static final class RouteResponse {
		final int status;
		final String body;

		RouteResponse(int status, String body) {
			this.status = status;
			this.body = body;
		}
	}
}
