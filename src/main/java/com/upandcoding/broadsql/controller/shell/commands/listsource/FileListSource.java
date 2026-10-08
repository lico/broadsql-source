package com.upandcoding.broadsql.controller.shell.commands.listsource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptTextIO;

/**
 * The original {@code <@fileName>} source: reads {@code fileName} line by line, trims each line, and
 * skips blank lines - exactly what {@code CommandUtils.substituteMacros} did before this class existed
 * (see docs/TODO.md, "Patterns and SQL productivity" for the verified-in-code description of that
 * original behavior). Deliberately unchanged: this is the source real, saved SQL may already depend on.
 *
 * <p>Relative paths resolve against the JVM's working directory (undocumented, pre-existing) - not
 * changed here either.
 */
public class FileListSource implements ListSource {

	private final String fileName;

	public FileListSource(String fileName) {
		this.fileName = fileName;
	}

	/**
	 * SPRINT 2409K: decoded with the same explicit rule as Scripts ({@link ScriptTextIO#decode}: BOM, else
	 * UTF-8, else windows-1252). This used a {@code FileReader}, i.e. the process default charset, which the
	 * launchers force to IBM850 ({@code -Dfile.encoding=Cp850}): a UTF-8 list file containing
	 * {@code Clôture} produced corrupted values (each UTF-8 byte read as one IBM850 character).
	 */
	@Override
	public List<String> values() throws BroadSQLException {
		List<String> lines = new ArrayList<>();
		String text;
		try {
			text = ScriptTextIO.decode(Files.readAllBytes(Paths.get(fileName))).getText();
		} catch (NoSuchFileException | InvalidPathException nsfe) {
			throw new BroadSQLException("File " + fileName + " not found");
		} catch (IOException ie) {
			throw new BroadSQLException("Error was encountered when opening file " + fileName);
		}
		try (BufferedReader br = new BufferedReader(new StringReader(text))) {
			String line;
			while ((line = br.readLine()) != null) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty()) {
					lines.add(trimmed);
				}
			}
		} catch (IOException ie) {
			throw new BroadSQLException("Error was encountered when opening file " + fileName);
		}
		return lines;
	}
}
