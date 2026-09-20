/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.upandcoding.broadsql.controller.shell.commands.extensions;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.sun.jna.Platform;

/**
 * Opens a file in Notepad: {@code EDIT <fileName>} (or {@code ED <fileName>}). With no
 * {@code fileName}, edits the last SQL query instead: writes it to a temporary {@code .sql} file,
 * opens that in Notepad, and on close reads it back and stores its (trimmed) content as the new
 * last query, echoed to the console the same way {@code SHOW QUERY} does; it does not execute the
 * query, matching the SQL*Plus {@code EDIT} convention already followed by {@code /} (re-run the
 * last query) and {@code SHOW QUERY}/{@code //} (display it) in this codebase: run it afterward
 * with {@code /}. Reports "No query in memory" and does nothing if there is no last query yet.
 * This no-argument mode is Windows-only, refusing with an error on any other OS; see
 * {@code docs/TODO.md} item 17.
 *
 * <p>This shells out to {@code notepad.exe} - it is Windows-only and always uses Notepad
 * specifically, regardless of what text editor is otherwise configured or set as the OS default.
 * BroadSQL waits for Notepad to close before returning to the prompt - see
 * {@link CommandUtils#openInNotepadAndWaitForClose(File)} for why that isn't simply a
 * {@code Process.waitFor()} on the launched {@code notepad.exe}.
 */
public class CommandEdit extends Command {

	private static final Logger log = LoggerFactory.getLogger(CommandEdit.class);

	public CommandEdit() {
		super("EDIT", "ED");
	}

    @Override
    public boolean isHidden() {
        return (false);
    }

    @Override
    public void execute(String query) throws BroadSQLException {
        if (query != null) {
            String expression = StringUtils.substringAfterLast(query.toUpperCase(), "RUNTIME");
            expression = expression.trim();
            Runtime runTime = Runtime.getRuntime();
            String winEditPlus = consoleSettings.getWinEditPlus();
            String dosCmd = StringUtils.substringAfterLast(query.toLowerCase(), "ed ");
            if (dosCmd==null || "".equals(dosCmd.trim())) {
                dosCmd = StringUtils.substringAfterLast(query.toLowerCase(), "edit ");
            }
            //log.debug("dosCmd="+dosCmd);
            if (dosCmd != null && !"".equals(dosCmd.trim())) {
                String dosCmdReal = "cmd /c notepad.exe " + dosCmd;
                try {
                    Process process = runTime.exec(dosCmdReal);
                    InputStream is = process.getInputStream();
                    InputStreamReader isr = new InputStreamReader(is);
                    BufferedReader br = new BufferedReader(isr);
                    String line;

                    while ((line = br.readLine()) != null) {
                        console.println(line);
                    }
                } catch (IOException ie) {
                    console.println(ie.getMessage());
                }
            } else {
                editLastQuery();
            }
            console.println("");
        }
    }

    /**
     * No-argument mode: edit the last SQL query in Notepad. See the class Javadoc for the
     * full behavior.
     */
    private void editLastQuery() throws BroadSQLException {
        if (!Platform.isWindows()) {
            throw new BroadSQLException("This command is only available for Windows OS");
        }
        if (StringUtils.isBlank(this.lastSQLQuery)) {
            console.println("No query in memory");
            return;
        }
        File tempFile = null;
        try {
            tempFile = File.createTempFile("broadsql_edit_", ".sql");
            Files.writeString(tempFile.toPath(), this.lastSQLQuery, StandardCharsets.UTF_8);

            CommandUtils.openInNotepadAndWaitForClose(tempFile);

            this.lastSQLQuery = Files.readString(tempFile.toPath(), StandardCharsets.UTF_8).trim();
            console.println(CommandUtils.toSingleLine(this.lastSQLQuery));
        } catch (IOException | InterruptedException e) {
            throw new BroadSQLException(e);
        } finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

    @Override
    public String getDescription() {
        return ("displays a file in the default windows text editor (notepad); with no file name, edits the last SQL query instead");
    }

	@Override
    public String getArguments() {
	    return "file name (optional; edits the last SQL query in Notepad if omitted)";
    }

	@Override
    public String getExamples() {
	    return "EDIT c:\\temp\\query*.sql;\nEDIT;";
    }
}
