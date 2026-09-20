package com.upandcoding.broadsql.controller.shell.commands.core.set;

import java.util.HashMap;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Sets the column separator used when exporting query results to text files:
 * {@code SET SEPARATOR <value>}.
 *
 * <p>{@code value} is optional; without it, the command only displays the current separator. When
 * given, a single character is used literally; otherwise it is looked up (case-insensitively) among
 * the named values {@code COMMA}, {@code DEFAULT} (tab), {@code ENTER}/{@code NEWLINE}, {@code PIPE},
 * {@code SEMICOLON}, {@code SPACE}, {@code TAB}. An unrecognized multi-character value silently falls
 * back to a pipe ({@code |}) rather than raising an error.
 *
 * <p>The current separator is always echoed back after the change, together with its name when it
 * matches one of the named values above.
 *
 * <p><b>Does not affect {@code PULL ... AS CSV/TXT}</b> (see
 * {@code com.upandcoding.broadsql.controller.shell.commands.core.export.CommandPull}, docs/PULL_TO_TEXT.md):
 * that command uses its own, separate {@code CsvSeparator} INI setting for {@code AS CSV} (semicolon if
 * absent) and always a literal tab for {@code AS TXT}, deliberately independent of this session-level
 * setting - only {@code EXPORT}/{@code DUMP}'s {@code .txt} output (not {@code .csv}, which is always
 * comma regardless of this setting) actually honors what {@code SET SEP} holds.
 */
public class CommandSetSeparator extends Command {
	
	private static final Logger log = LoggerFactory.getLogger(CommandSetSeparator.class);

    static final HashMap<String, Character> names = new HashMap<String, Character>();

    public CommandSetSeparator() {
        super("SET SEPARATOR", "SEPARATOR", "SEP", "SET SEP");
    }

    {
        names.put("COMMA",      ',');
        names.put("DEFAULT",    '\t');
        names.put("ENTER",      '\n');
        names.put("NEWLINE",    '\n');
        names.put("PIPE",       '|');
        names.put("SEMICOLON",  ';');
        names.put("SPACE",      ' ');
        names.put("TAB",        '\t');
    }

    @Override
    public void execute(String query) throws BroadSQLException {

    	String[] args = parseArgs(query);

		char nSep = this.getSeparator();
		if (CommandUtils.isValidArgs(args)) {
			nSep = '|';
			String newSep = args[0].trim();
			if (newSep.length() == 1) {
				nSep = newSep.charAt(0);
			} else {
				if (names.containsKey(newSep.toUpperCase())) {
					nSep = names.get(newSep.toUpperCase());
				}
			}
			this.setSeparator(nSep);
		}
    	
        String display = "" + this.getSeparator();
        
        if (names != null && !names.isEmpty()) {
            for (String name : names.keySet()) {
                char c = names.get(name);
                if (c == this.getSeparator()) {
                    display = display + " (" + name + ")";
                    break;
                }
            }
        }
        
        console.println("Columns separator for text export files: " + display);
        console.println("");
    }

    @Override
    public String getDescription() {
        return ("Specifies a columns separator for export to text files (default is pipe)");
    }

	@Override
    public String getArguments() {
	    return "<caracter> (optional). If no argument, displays current columns separator";
    }

	@Override
    public String getExamples() {
	    return "SET SEP |;";
    }
}
