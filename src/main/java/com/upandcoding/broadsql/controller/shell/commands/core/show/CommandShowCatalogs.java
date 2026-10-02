package com.upandcoding.broadsql.controller.shell.commands.core.show;

import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;

/**
 * Lists the catalogs available in the current database: {@code SHOW CATALOGS}.
 *
 * <p>{@code catalogName} is optional and acts as a case-insensitive "contains" filter (a plain
 * substring search, not a {@code %}-wildcard pattern): only catalogs whose name includes it are
 * listed. With no argument, every catalog is listed.
 */
public class CommandShowCatalogs extends Command {

	public CommandShowCatalogs() {
		super("SHOW CATALOGS", "SH CA", "SHCA");
	}

    @Override
    public void execute(String query) throws BroadSQLException {
    	String[] args = parseArgs(query);

		String catalogNameFilter = null;
		if (CommandUtils.isValidArgs(args)) {
			catalogNameFilter  =args[0].trim();
			if (StringUtils.isBlank(catalogNameFilter)) {
				catalogNameFilter = null;
			}
		}

        try {
            TreeSet<String> catalogs = sqlDatabase.getCatalogs(catalogNameFilter); 
            shellConsolePrinter.printCatalogs(catalogs);

        } catch (BroadSQLException se) {
            throw new BroadSQLException (se);
        }
    }

    @Override
    public String getDescription() {
        return ("Displays a list of catalogs in the current database");
    }

	@Override
    public String getArguments() {
	    return "<catalogName> (optional) a search text, can be a catalog name or a part of it";
    }

	@Override
    public String getExamples() {
	    return "SHOW CATALOGS;\treturns all catalogs in current database\n\tSHOW CATALOGS MYCAT;\treturns all catalogs which name contains string MYCAT, ignoring case";
    }
}
