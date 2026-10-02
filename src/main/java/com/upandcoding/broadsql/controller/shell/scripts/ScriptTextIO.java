package com.upandcoding.broadsql.controller.shell.scripts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.upandcoding.broadsql.dao.util.SafeFileWriter;

/**
 * SPRINT 1909S: the ONE way BroadSQL reads and writes the text of a Script. The Scripts Library
 * operations, {@link ScriptExecutor}, {@code LIB SHOW} and the BroadSQL Editor all go through here, so
 * they always interpret a given file identically.
 *
 * <p>Reading policy (deliberately minimal, no charset auto-detection):
 * <ol>
 * <li>a supported BOM (UTF-8, UTF-16 LE/BE) declares the charset; the BOM is stripped from the text;
 * <li>otherwise strict UTF-8 if the whole file is valid UTF-8;
 * <li>otherwise {@link #LEGACY_FALLBACK} (windows-1252, the Western "ANSI" code page Windows editors use
 * for files not saved as UTF-8).
 * </ol>
 * SPRINT 2409K: the third step used to be {@link Charset#defaultCharset()}. The BroadSQL launchers start
 * the JVM with {@code -Dfile.encoding=Cp850} (for the Windows console), so the process default charset is
 * IBM850, an OEM console code page no editor saves scripts in: an ANSI script containing
 * {@code 'Clôture'} (byte {@code 0xF4}) reached JDBC as {@code 'Cl¶ture'}. Reading no longer depends on
 * the platform or on {@code file.encoding}.
 * BroadSQL does not attempt general legacy-encoding detection. New files are written as UTF-8 without a
 * BOM; an existing file is written back in the charset/BOM state it was read in, and a save whose text
 * cannot be encoded in that charset is refused before anything is written.
 *
 * <p>A file is "not text" (see {@link #looksBinary(byte[], int)}) when its first {@value #SNIFF_BYTES}
 * bytes contain a NUL byte, or more than {@value #CONTROL_PERCENT_LIMIT}% control characters other than
 * tab, CR, LF and form feed. No extension is ever consulted.
 */
public final class ScriptTextIO {

	static final int SNIFF_BYTES = 8192;
	static final int CONTROL_PERCENT_LIMIT = 30;

	/** The explicit charset for text that has no BOM and is not valid UTF-8 (never the platform default). */
	public static final Charset LEGACY_FALLBACK = Charset.forName("windows-1252");

	private ScriptTextIO() {
	}

	/** Text plus the encoding state it was read in, so a save can reproduce it. */
	public static final class TextContent {
		private final String text;
		private final Charset charset;
		private final boolean bom;

		public TextContent(String text, Charset charset, boolean bom) {
			this.text = text;
			this.charset = charset;
			this.bom = bom;
		}

		public String getText() {
			return text;
		}

		public Charset getCharset() {
			return charset;
		}

		public boolean hasBom() {
			return bom;
		}

		/** Same encoding state, new text (used when the editor saves edited content). */
		public TextContent withText(String newText) {
			return new TextContent(newText, charset, bom);
		}

		/** The encoding state a brand-new file gets: UTF-8, no BOM. */
		public static TextContent newFile(String text) {
			return new TextContent(text, StandardCharsets.UTF_8, false);
		}
	}

	/** Thrown when a file exists but is not a text file, or cannot be read. */
	public static class ScriptTextException extends IOException {
		private static final long serialVersionUID = 1L;

		public ScriptTextException(String message) {
			super(message);
		}
	}

	/** Reads {@code file} as text, refusing binary content. */
	public static TextContent read(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		if (looksBinary(bytes, bytes.length)) {
			throw new ScriptTextException("'" + file + "' is not a text file");
		}
		return decode(bytes);
	}

	/** Decodes bytes according to the reading policy (does not check for binary content). */
	public static TextContent decode(byte[] bytes) {
		if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
			return new TextContent(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8), StandardCharsets.UTF_8, true);
		}
		if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
			return new TextContent(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE), StandardCharsets.UTF_16BE, true);
		}
		if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
			return new TextContent(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE, true);
		}
		try {
			String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
			return new TextContent(text, StandardCharsets.UTF_8, false);
		} catch (CharacterCodingException e) {
			return new TextContent(new String(bytes, LEGACY_FALLBACK), LEGACY_FALLBACK, false);
		}
	}

	/**
	 * Writes {@code content} atomically in its own charset/BOM state. Nothing is written (and the
	 * existing file is untouched) if the text cannot be encoded in that charset.
	 */
	public static void write(Path file, TextContent content) throws IOException {
		byte[] body = encode(content.getText(), content.getCharset());
		byte[] bom = content.hasBom() ? bomFor(content.getCharset()) : new byte[0];
		byte[] all = new byte[bom.length + body.length];
		System.arraycopy(bom, 0, all, 0, bom.length);
		System.arraycopy(body, 0, all, bom.length, body.length);
		SafeFileWriter.writeBytes(file, all);
	}

	/** Strict encode: refuses (rather than replaces) characters the charset cannot represent. */
	static byte[] encode(String text, Charset charset) throws IOException {
		try {
			ByteBuffer buffer = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text));
			return Arrays.copyOfRange(buffer.array(), buffer.arrayOffset(), buffer.arrayOffset() + buffer.limit());
		} catch (CharacterCodingException e) {
			throw new ScriptTextException("The text contains characters that cannot be saved in this file's encoding ("
					+ charset.displayName() + "); nothing was written");
		}
	}

	private static byte[] bomFor(Charset charset) {
		if (StandardCharsets.UTF_8.equals(charset)) {
			return new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
		}
		if (StandardCharsets.UTF_16BE.equals(charset)) {
			return new byte[] { (byte) 0xFE, (byte) 0xFF };
		}
		if (StandardCharsets.UTF_16LE.equals(charset)) {
			return new byte[] { (byte) 0xFF, (byte) 0xFE };
		}
		return new byte[0];
	}

	/** Cheap text/binary check reading at most the head of the file. */
	public static boolean isTextFile(Path file) {
		byte[] head = new byte[SNIFF_BYTES];
		try (InputStream in = Files.newInputStream(file)) {
			int n = in.readNBytes(head, 0, head.length);
			return !looksBinary(head, n);
		} catch (IOException e) {
			return false;
		}
	}

	static boolean looksBinary(byte[] bytes, int length) {
		int n = Math.min(length, SNIFF_BYTES);
		if (n == 0) {
			return false;
		}
		// UTF-16 text legitimately contains NUL bytes: a UTF-16 BOM marks it as text.
		if (n >= 2 && (((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) || ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE))) {
			return false;
		}
		int control = 0;
		for (int i = 0; i < n; i++) {
			int b = bytes[i] & 0xFF;
			if (b == 0) {
				return true;
			}
			if (b < 32 && b != '\t' && b != '\n' && b != '\r' && b != '\f') {
				control++;
			}
		}
		return control * 100 > n * CONTROL_PERCENT_LIMIT;
	}
}
