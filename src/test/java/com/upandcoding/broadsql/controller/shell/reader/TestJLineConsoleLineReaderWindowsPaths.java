package com.upandcoding.broadsql.controller.shell.reader;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.upandcoding.broadsql.controller.shell.commands.CommandList;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * GitHub #156 - "Accept normal Windows paths with single backslashes in {@code <@filename>}".
 *
 * <p><b>Root cause, found empirically</b> (a throwaway {@code JLineConsoleLineReader.createForTestingWithRealKeyBindings}
 * probe, not by reading JLine's source): the corruption was never in {@code CommandUtils#substituteMacros},
 * {@link com.upandcoding.broadsql.controller.shell.commands.listsource.ListSourceResolver}, or
 * {@link com.upandcoding.broadsql.controller.shell.commands.listsource.FileListSource} - none of those do
 * any escape processing of their own, and {@link TestCommandUtilsSubstituteMacros} already proved that
 * layer round-trips a real (backslash-separated, on Windows) {@code @TempDir} path unchanged. The
 * corruption happened one layer up: JLine enables bash-style history event expansion ({@code !}/{@code ^})
 * by default, and as part of that, {@code LineReaderImpl.expandEvents} treats a bare backslash as a
 * generic escape character and silently drops it from the accepted line regardless of the following
 * character - typing {@code <@c:\temp\toto.sql>} through the real key-binding path returned
 * {@code <@c:temptoto.sql>} before the fix (every backslash gone, corrupting the path; not converted to
 * control characters, but just as unusable), and the doubled-backslash workaround
 * ({@code <@c:\\temp\\toto.sql>}) happened to survive only because each pair collapsed to one real
 * backslash. The fix, in {@link JLineConsoleLineReader#buildLineReader}, sets
 * {@code LineReader.Option.DISABLE_EVENT_EXPANSION} - BroadSQL has no bash-style history-reference syntax,
 * so disabling that JLine feature has no product-visible effect beyond no longer mangling backslashes.
 * {@code BasicLineReader} (the {@code activatejline=OFF} path, {@code java.io.Console#readLine()}) was
 * never affected and needed no change.
 *
 * <p>This class exercises the real key-binding path end to end (the same seam
 * {@code TestJLineConsoleLineReaderRealKeyBindings} and {@code TestEntityCompletion} use, over piped
 * streams rather than a physical TTY, so real JLine key dispatch - not merely a direct method call -
 * processes every keystroke); the last two tests go one step further and feed the exact string JLine
 * returns into {@link CommandUtils#substituteMacros} against a real file on disk, to verify that the
 * filename reaching the file-loading layer is exactly the filename typed, not merely that parsing
 * succeeds.
 */
class TestJLineConsoleLineReaderWindowsPaths {

	private String driveKeystrokes(String keystrokes) throws Exception {
		PipedOutputStream keyboardOut = new PipedOutputStream();
		PipedInputStream keyboardIn = new PipedInputStream(keyboardOut);
		ByteArrayOutputStream screen = new ByteArrayOutputStream();

		JLineConsoleLineReader reader = JLineConsoleLineReader.createForTestingWithRealKeyBindings(
				keyboardIn, screen, null, null, new CommandList(), null);
		try {
			byte[] bytes = keystrokes.getBytes(StandardCharsets.UTF_8);
			Thread feeder = new Thread(() -> {
				try {
					for (byte b : bytes) {
						keyboardOut.write(b);
						keyboardOut.flush();
						Thread.sleep(5);
					}
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});
			feeder.setDaemon(true);
			feeder.start();
			return reader.readLine("test> ");
		} finally {
			reader.close();
		}
	}

	/** Asserts every character JLine returned is exactly what was typed, and in particular that no backslash sequence turned into a control character (the acceptance criteria's specific worry about {@code \t}, {@code \n}, {@code \r}, {@code \b}, {@code \f}). */
	private void assertSurvivesRealKeyDispatchUnchanged(String line) throws Exception {
		String result = driveKeystrokes(line + "\n");
		Assertions.assertEquals(line, result, "JLine must return the line exactly as typed");
		for (int i = 0; i < result.length(); i++) {
			Assertions.assertFalse(Character.isISOControl(result.charAt(i)),
					"no control character expected in: " + result + " (char " + i + " was 0x" + Integer.toHexString(result.charAt(i)) + ")");
		}
	}

	@Test
	void absoluteWindowsPathWithSingleBackslashesSurvives() throws Exception {
		assertSurvivesRealKeyDispatchUnchanged("<@c:\\temp\\toto.sql>");
	}

	/** The three sequences the issue calls out by name: a backslash immediately followed by t/n/b, which - if ever misread as a Java/C-style escape - would become an actual TAB/LF/backspace control character. */
	@Test
	void pathsStartingWithBackslashTNBSequencesStayLiteral() throws Exception {
		assertSurvivesRealKeyDispatchUnchanged("<@c:\\temp\\toto.sql>"); // \t
		assertSurvivesRealKeyDispatchUnchanged("<@c:\\new\\report.sql>"); // \n
		assertSurvivesRealKeyDispatchUnchanged("<@c:\\backup\\file.sql>"); // \b
	}

	@Test
	void nestedWindowsPathSurvives() throws Exception {
		assertSurvivesRealKeyDispatchUnchanged("<@c:\\temp\\subdir\\toto.sql>");
	}

	@Test
	void relativeWindowsPathSurvives() throws Exception {
		assertSurvivesRealKeyDispatchUnchanged("<@temp\\toto.sql>");
		assertSurvivesRealKeyDispatchUnchanged("<@..\\scripts\\toto.sql>");
	}

	@Test
	void windowsPathWithSpacesSurvives() throws Exception {
		assertSurvivesRealKeyDispatchUnchanged("<@c:\\my folder\\my file.sql>");
	}

	@Test
	void doubledBackslashLegacyFormIsPreservedAsTyped() throws Exception {
		// Backward compatibility (issue #156): the doubled form must keep working. It is no longer
		// required, but nothing here rewrites or collapses it - it now passes through exactly as every
		// other keystroke does, and java.io.File on Windows already normalizes the repeated separator
		// (verified empirically: File("C:\\\\temp\\\\x.txt").exists() resolves the same as the single
		// separator - see docs/TECHNICAL_CHANGE.md, 22/09/2026).
		assertSurvivesRealKeyDispatchUnchanged("<@c:\\\\temp\\\\toto.sql>");
	}

	@Test
	void unixForwardSlashPathsAreNotRegressed() throws Exception {
		assertSurvivesRealKeyDispatchUnchanged("<@/tmp/toto.sql>");
		assertSurvivesRealKeyDispatchUnchanged("<@./scripts/toto.sql>");
	}

	/**
	 * Parsing no longer corrupts a UNC-shaped token either, purely as a side effect of fixing the general
	 * backslash-stripping bug - this is not a claim that BroadSQL newly supports UNC shares (issue #156:
	 * "do not add UNC support as a new feature... simply report the current limitation"). Reaching a real
	 * {@code \\server\share\...} path would still go through the same plain {@code java.io.FileReader}
	 * {@link com.upandcoding.broadsql.controller.shell.commands.listsource.FileListSource} always used,
	 * unchanged by this fix; that is an environment/network question this test suite cannot exercise.
	 */
	@Test
	void uncShapedTokenIsNoLongerCorruptedByParsingAloneNoNewSupportClaimed() throws Exception {
		assertSurvivesRealKeyDispatchUnchanged("<@\\\\server\\share\\file.sql>");
	}

	// ---- full pipeline: real JLine key dispatch -> CommandUtils.substituteMacros -> a real file on disk ----

	@Test
	void aRealWindowsPathTypedThroughJLineLoadsTheExactFileItNames(@TempDir Path tempDir) throws Exception {
		Path subdir = tempDir.resolve("temp");
		Files.createDirectories(subdir);
		Path file = subdir.resolve("toto.sql");
		Files.writeString(file, "A\nB\n", StandardCharsets.UTF_8);

		// file.toString() on Windows is already backslash-separated (e.g. "...\temp\toto.sql") - typed
		// here exactly as a user would type it, through the real JLine key-binding path.
		String typedLine = "SELECT * FROM T WHERE ID IN <@" + file + ">";
		String lineAfterJLine = driveKeystrokes(typedLine + "\n");
		Assertions.assertEquals(typedLine, lineAfterJLine, "JLine must not alter the typed path");

		String result = CommandUtils.substituteMacros(lineAfterJLine);

		Assertions.assertEquals("SELECT * FROM T WHERE ID IN ('A','B')", result);
	}

	@Test
	void aForwardSlashPathTypedThroughJLineStillLoadsTheSameFile(@TempDir Path tempDir) throws Exception {
		Path file = tempDir.resolve("ids.txt");
		Files.writeString(file, "X\nY\n", StandardCharsets.UTF_8);

		// The same file, addressed with forward slashes (accepted by java.io.File on Windows too) -
		// proves the Unix-style form was never the one at risk and still works after the fix.
		String forwardSlashPath = file.toString().replace('\\', '/');
		String typedLine = "SELECT * FROM T WHERE ID IN <@" + forwardSlashPath + ">";
		String lineAfterJLine = driveKeystrokes(typedLine + "\n");
		Assertions.assertEquals(typedLine, lineAfterJLine);

		String result = CommandUtils.substituteMacros(lineAfterJLine);

		Assertions.assertEquals("SELECT * FROM T WHERE ID IN ('X','Y')", result);
	}
}
