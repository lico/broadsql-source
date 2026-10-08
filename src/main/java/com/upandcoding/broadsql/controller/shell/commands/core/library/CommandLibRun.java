package com.upandcoding.broadsql.controller.shell.commands.core.library;

import com.upandcoding.broadsql.controller.shell.completion.CompletionEntityType;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptArguments;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptCall;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptExecutor;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptResolver;

/**
 * Runs a Script from the Scripts Library: {@code LIB RUN <script> [name=value ...]}.
 *
 * <p>{@code script} is a path relative to the Scripts Library root, taken exactly as written:
 * {@code LIB RUN foo.bsql} runs {@code <ScriptsLibrary>/foo.bsql}, {@code LIB RUN maintenance/foo.bsql}
 * runs {@code <ScriptsLibrary>/maintenance/foo.bsql}. No extension is added, no alias or file-name search
 * is done, and the path cannot leave the library (use {@code @<path>} for a Script located elsewhere).
 *
 * <p>Arguments are named, as for {@code @}: {@code LIB RUN report.bsql customer_id=42 country='FR'} assigns the
 * SQL scripting variables {@code customer_id} and {@code country} before the Script starts, and the Script reads
 * them as {@code ${customer_id}} and {@code ${country}}. Positional parameters {@code %1} to {@code %9} were
 * removed. Execution, arguments, the final status line and errors are those of {@code @}; only the reference
 * form differs.
 *
 * <p>See [Named Script arguments](../scripting_arguments.md#named-script-arguments) in the SQL scripting guide.
 */
public class CommandLibRun extends Command {

	public CommandLibRun() {
		super("LIB RUN", "LI RU", "LIRU");
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] call = ScriptArguments.splitReference(textAfterKeyword(query));
		if (StringUtils.isBlank(call[0])) {
			throw new BroadSQLException("You must provide the name of a Scripts Library script");
		}
		ScriptResolver resolver = new ScriptResolver(consoleSettings.getScriptsLibraryPath());
		String reference = call[0];
		ScriptExecutor.forCommand(this).run(ScriptCall.of(reference, () -> resolver.resolveLibraryScript(reference).getPath(), call[1]));
	}

	@Override
	public String getDescription() {
		return "Runs a Script from the Scripts Library";
	}

	@Override
	public String getDetailedDescription() {
		return "Runs a Script stored in the Scripts Library, the same way `@` does. The name is a path relative to the library root, exactly as "
				+ "written: `LIB RUN maintenance/foo.bsql` runs `maintenance/foo.bsql`. No extension is added and the path cannot leave the library. "
				+ "Arguments are named, `name=value`, and read in the Script as `${name}`.";
	}

	@Override
	public List<CompletionEntityType> getCompletionArguments() {
		return List.of(CompletionEntityType.SCRIPT);
	}

	@Override
	public String getArguments() {
		return "<script> (mandatory) a path relative to the Scripts Library, then optional name=value arguments";
	}

	@Override
	public String getExamples() {
		return "LIB RUN maintenance/cleanup.bsql\nLIB RUN \"folder/my script.bsql\" region='EU' year=2026";
	}
}
