package com.upandcoding.broadsql.dao.util;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestContentHasher {

	@Test
	void sameContentProducesTheSameHash() {
		Assertions.assertEquals(ContentHasher.sha256Hex("select 1;"), ContentHasher.sha256Hex("select 1;"));
	}

	@Test
	void differentContentProducesDifferentHashes() {
		Assertions.assertNotEquals(ContentHasher.sha256Hex("select 1;"), ContentHasher.sha256Hex("select 2;"));
	}

	@Test
	void nullIsTreatedAsEmptyContent() {
		Assertions.assertEquals(ContentHasher.sha256Hex(""), ContentHasher.sha256Hex(null));
	}

	@Test
	void producesA64CharacterLowercaseHexString() {
		String hash = ContentHasher.sha256Hex("abc");
		Assertions.assertEquals(64, hash.length());
		Assertions.assertTrue(hash.matches("[0-9a-f]{64}"));
	}
}
