package com.upandcoding.broadsql.controller.shell.scripts;

import java.nio.file.Path;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/**
 * SPRINT 0110A: one call of a Script, as {@link ScriptExecutor#run(ScriptCall)} receives it from {@code @},
 * {@code LIB RUN}, the BroadSQL Editor and {@code DUMP}/{@code PULL}: the script reference as written (shown in
 * the final status line), how to resolve it to a file (done by the executor, as part of the run's preflight, so a
 * reference that cannot be resolved makes the run {@code FAILED}), and the named arguments as written.
 */
public final class ScriptCall {

	/** Resolves the reference to an existing file ({@link ScriptResolver}). */
	@FunctionalInterface
	public interface Resolver {
		Path resolve() throws BroadSQLException;
	}

	private final String reference;
	private final Resolver resolver;
	private final String argumentText;

	private ScriptCall(String reference, Resolver resolver, String argumentText) {
		this.reference = reference;
		this.resolver = resolver;
		this.argumentText = argumentText == null ? "" : argumentText;
	}

	public static ScriptCall of(String reference, Resolver resolver, String argumentText) {
		return new ScriptCall(reference, resolver, argumentText);
	}

	/** A call of an already-resolved file, named by its file name. */
	public static ScriptCall ofPath(Path script, String argumentText) {
		return new ScriptCall(script.getFileName() == null ? script.toString() : script.getFileName().toString(), () -> script, argumentText);
	}

	public String getReference() {
		return reference;
	}

	public Path resolve() throws BroadSQLException {
		return resolver.resolve();
	}

	/** The {@code name=value ...} text that followed the reference; empty when none. */
	public String getArgumentText() {
		return argumentText;
	}
}
