package com.upandcoding.broadsql.controller.shell.scripts;

import java.security.SecureRandom;
import java.util.function.Supplier;

/**
 * SPRINT 0110A: the Run ID of a top-level Script run (spec section 14.4): 8 characters from {@code 0-9A-Z},
 * random, shared by the nested runs, shown in the final status line and placed in the application log's
 * diagnostic context under {@link #MDC_KEY}. Not persisted anywhere.
 */
public final class RunIds {

	/** The SLF4J MDC key holding the current Run ID while a run is in progress. */
	public static final String MDC_KEY = "broadsqlRunId";

	private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Supplier<String> RANDOM_IDS = RunIds::random;
	private static volatile Supplier<String> generator = RANDOM_IDS;

	private RunIds() {
	}

	public static String next() {
		return generator.get();
	}

	static String random() {
		char[] id = new char[8];
		for (int i = 0; i < id.length; i++) {
			id[i] = ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));
		}
		return new String(id);
	}

	/** Tests only: a deterministic generator; {@code null} restores the random one. */
	public static void setGeneratorForTests(Supplier<String> testGenerator) {
		generator = testGenerator == null ? RANDOM_IDS : testGenerator;
	}
}
