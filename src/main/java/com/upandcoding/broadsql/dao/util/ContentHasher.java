package com.upandcoding.broadsql.dao.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 hashing for plain text, used to detect logical content changes (SPRINT 0917-01's revision
 * vault compares a body hash and a metadata hash rather than storing/diffing full snapshots on every
 * comparison - see {@code RevisionVault}'s "logical state" javadoc). Not a security/integrity
 * primitive - a fast, collision-safe-enough fingerprint for "did this text change since the last
 * revision," nothing more.
 */
public final class ContentHasher {

	private ContentHasher() {
	}

	public static String sha256Hex(String content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is guaranteed available on every standard JVM - this is unreachable in practice.
			throw new IllegalStateException(e);
		}
	}
}
