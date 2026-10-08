package com.upandcoding.broadsql.controller.shell.output;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestCapturingConsole {

	@Test
	void capturesWrittenTextInsteadOfSystemOut() {
		CapturingConsole console = new CapturingConsole();

		console.println("hello");
		console.println("world");

		Assertions.assertTrue(console.getOutput().contains("hello"));
		Assertions.assertTrue(console.getOutput().contains("world"));
	}
}
