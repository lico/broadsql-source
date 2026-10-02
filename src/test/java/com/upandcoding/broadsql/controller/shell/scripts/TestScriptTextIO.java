package com.upandcoding.broadsql.controller.shell.scripts;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPRINT 1909S: {@link ScriptTextIO}. Reading: supported BOM, then strict UTF-8, then (SPRINT 2409K) the
 * explicit windows-1252 fallback, never the platform default charset. No charset detection.
 */
class TestScriptTextIO {

	@TempDir
	Path tmp;

	private Path bytes(String name, byte[] content) throws IOException {
		return Files.write(tmp.resolve(name), content);
	}

	@Test
	void utf8WithoutBomIsReadAsUtf8() throws IOException {
		Path file = bytes("a.bsql", "SELECT 'é€';".getBytes(StandardCharsets.UTF_8));
		ScriptTextIO.TextContent content = ScriptTextIO.read(file);
		Assertions.assertEquals("SELECT 'é€';", content.getText());
		Assertions.assertEquals(StandardCharsets.UTF_8, content.getCharset());
		Assertions.assertFalse(content.hasBom());
	}

	@Test
	void utf8BomIsStrippedAndRemembered() throws IOException {
		byte[] body = "SELECT 1;".getBytes(StandardCharsets.UTF_8);
		byte[] all = new byte[body.length + 3];
		all[0] = (byte) 0xEF;
		all[1] = (byte) 0xBB;
		all[2] = (byte) 0xBF;
		System.arraycopy(body, 0, all, 3, body.length);
		ScriptTextIO.TextContent content = ScriptTextIO.read(bytes("bom.bsql", all));
		Assertions.assertEquals("SELECT 1;", content.getText());
		Assertions.assertTrue(content.hasBom());
		Assertions.assertEquals(StandardCharsets.UTF_8, content.getCharset());
	}

	@Test
	void utf16BomFilesAreTextAndDecodeCorrectly() throws IOException {
		byte[] body = "SELECT 1;".getBytes(StandardCharsets.UTF_16LE);
		byte[] all = new byte[body.length + 2];
		all[0] = (byte) 0xFF;
		all[1] = (byte) 0xFE;
		System.arraycopy(body, 0, all, 2, body.length);
		ScriptTextIO.TextContent content = ScriptTextIO.read(bytes("u16.bsql", all));
		Assertions.assertEquals("SELECT 1;", content.getText());
		Assertions.assertEquals(StandardCharsets.UTF_16LE, content.getCharset());
		Assertions.assertTrue(content.hasBom());
	}

	@Test
	void nonUtf8BytesFallBackToWindows1252NotThePlatformDefault() {
		// 0xE9 alone is not valid UTF-8 (a lone lead byte); in windows-1252 it is U+00E9, whatever the
		// platform default is (IBM850 under the launchers' -Dfile.encoding=Cp850)
		byte[] legacy = new byte[] { 'S', 'E', 'L', 'E', 'C', 'T', ' ', '\'', (byte) 0xE9, '\'' };
		ScriptTextIO.TextContent content = ScriptTextIO.decode(legacy);
		Assertions.assertEquals("SELECT 'é'", content.getText());
		Assertions.assertEquals(Charset.forName("windows-1252"), content.getCharset());
		Assertions.assertFalse(content.hasBom());
	}

	@Test
	void anUnreadableLegacyFileStillAppearsAsText() throws IOException {
		byte[] legacy = new byte[] { 'a', (byte) 0xE9, 'b' };
		Path file = bytes("legacy.txt", legacy);
		Assertions.assertTrue(ScriptTextIO.isTextFile(file));
		Assertions.assertNotNull(ScriptTextIO.read(file).getText());
	}

	@Test
	void writeReproducesTheEncodingStateItWasReadIn() throws IOException {
		byte[] body = "SELECT 'é';".getBytes(StandardCharsets.UTF_8);
		byte[] all = new byte[body.length + 3];
		all[0] = (byte) 0xEF;
		all[1] = (byte) 0xBB;
		all[2] = (byte) 0xBF;
		System.arraycopy(body, 0, all, 3, body.length);
		Path file = bytes("keep.bsql", all);
		ScriptTextIO.TextContent read = ScriptTextIO.read(file);
		ScriptTextIO.write(file, read.withText("SELECT 'ü';"));
		byte[] after = Files.readAllBytes(file);
		Assertions.assertEquals((byte) 0xEF, after[0]);
		Assertions.assertEquals("SELECT 'ü';", new String(after, 3, after.length - 3, StandardCharsets.UTF_8));
	}

	@Test
	void newFilesAreUtf8WithoutBom() throws IOException {
		Path file = tmp.resolve("new.bsql");
		ScriptTextIO.write(file, ScriptTextIO.TextContent.newFile("SELECT 'é';"));
		Assertions.assertArrayEquals("SELECT 'é';".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
	}

	@Test
	void textThatCannotBeEncodedInTheFilesCharsetIsRefusedAndTheFileIsUntouched() throws IOException {
		Path file = tmp.resolve("ascii.bsql");
		Files.write(file, "SELECT 1;".getBytes(StandardCharsets.US_ASCII));
		ScriptTextIO.TextContent asciiState = new ScriptTextIO.TextContent("SELECT 1;", StandardCharsets.US_ASCII, false);
		IOException e = Assertions.assertThrows(IOException.class, () -> ScriptTextIO.write(file, asciiState.withText("SELECT '€';")));
		Assertions.assertTrue(e.getMessage().contains("nothing was written"), e.getMessage());
		Assertions.assertEquals("SELECT 1;", Files.readString(file));
	}

	@Test
	void nulBytesMeanNotText() throws IOException {
		Path file = bytes("bin.dat", new byte[] { 'M', 'Z', 0, 1, 2, 3 });
		Assertions.assertFalse(ScriptTextIO.isTextFile(file));
		IOException e = Assertions.assertThrows(IOException.class, () -> ScriptTextIO.read(file));
		Assertions.assertTrue(e.getMessage().contains("not a text file"), e.getMessage());
	}

	@Test
	void controlHeavyContentMeansNotText() throws IOException {
		byte[] noise = new byte[200];
		for (int i = 0; i < noise.length; i++) {
			noise[i] = (byte) (1 + (i % 6)); // control characters, no NUL
		}
		Assertions.assertFalse(ScriptTextIO.isTextFile(bytes("noise.bin", noise)));
	}

	@Test
	void ordinaryControlCharactersAreStillText() throws IOException {
		Assertions.assertTrue(ScriptTextIO.isTextFile(bytes("t.txt", "a\tb\r\nc\fd".getBytes(StandardCharsets.UTF_8))));
	}

	@Test
	void emptyFileIsText() throws IOException {
		Assertions.assertTrue(ScriptTextIO.isTextFile(bytes("empty.bsql", new byte[0])));
	}

	@Test
	void extensionsAreNeverConsulted() throws IOException {
		byte[] sql = "SELECT 1;".getBytes(StandardCharsets.UTF_8);
		for (String name : new String[] { "a.bsql", "a.sql", "a.txt", "a.foo", "a", "a.exe", "a.png" }) {
			Assertions.assertTrue(ScriptTextIO.isTextFile(bytes(name, sql)), name);
		}
	}
}
