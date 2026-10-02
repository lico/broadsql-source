package com.upandcoding.broadsql.controller.shell.commands.core.sql;

import org.apache.commons.lang3.StringUtils;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptArguments;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptCall;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptExecutor;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptResolver;

/**
 * Runs a Script: {@code @<reference> [name=value ...]}. The preferred, concise way to execute a Script.
 *
 * <p>A Script is a text file of SQL statements, BroadSQL commands, or both, separated by {@code ;}. The file
 * extension means nothing to BroadSQL. The reference is resolved the same way for every command and the editor.
 *
 * <p>{@code @foo.bsql} and {@code @maintenance/foo.bsql} are relative to the Scripts Library root, never to
 * the working directory, and no extension is ever guessed: {@code @foo} is the file named {@code foo}.
 *
 * <p>{@code @./foo.bsql} is relative to the working directory when typed at the prompt, and to the directory
 * of the running Script when written inside a Script, so a folder of Scripts can be moved as a bundle.
 *
 * <p>{@code @C:\temp\foo.bsql} or {@code @/tmp/foo.bsql} is an explicit path to a Script outside the library.
 * Quote a reference that contains spaces.
 *
 * <p>Arguments are named: {@code @report.bsql customer_id=42 country='FR'}. Each one assigns a SQL scripting
 * variable before the Script starts, exactly like {@code LET customer_id = 42;}, and the Script reads it as
 * {@code ${customer_id}}. A value is a number, a single-quoted string, {@code TRUE}, {@code FALSE}, {@code NULL}
 * or one {@code ${other}} variable. Every value is computed before any is assigned, and a call that fails assigns
 * none of them. There is no limit on the number of arguments. Assigned values remain in the session after the
 * Script returns. A Script can require arguments with a {@code -- @params: customer_id, country} comment line: each
 * call must then pass them explicitly.
 *
 * <p>Positional parameters {@code %1} to {@code %9} were removed: {@code @report.bsql 42 FR} now fails with an
 * explanation. Write {@code @report.bsql customer_id=42 country='FR'} and use {@code ${customer_id}} in the Script.
 *
 * <p>At the end of a Script started at the prompt, a status line gives the result ({@code SUCCESS},
 * {@code COMPLETED_WITH_ERRORS}, {@code FAILED} or {@code CANCELLED}), the number of statements run and failed,
 * and a run identifier. Execution is shared with {@code LIB RUN} and the BroadSQL Editor.
 *
 * <p>See [Running Scripts](../scripting_running.md), and the [Scripting overview](../library_and_scripts.md) for
 * variables, named arguments, {@code ECHO}, {@code OUTPUT}, {@code ON ERROR} and nested Scripts.
 */
public class CommandExternalFile extends Command {

	public CommandExternalFile() {
		super(ScriptResolver.RUN_KEYWORD);
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		String[] call = ScriptArguments.splitReference(textAfterKeyword(query));
		if (StringUtils.isBlank(call[0])) {
			throw new BroadSQLException("You must provide a script name or path after @");
		}
		ScriptResolver resolver = new ScriptResolver(consoleSettings.getScriptsLibraryPath());
		String reference = call[0];
		ScriptExecutor.forCommand(this).run(ScriptCall.of(reference,
				() -> resolver.resolveForExecution(reference, getConsoleCommandInterpreter().getScriptContext()).getPath(), call[1]));
	}

	@Override
	public String getDescription() {
		return "Runs a Script: a text file of SQL statements and BroadSQL commands";
	}

	@Override
	public String getDetailedDescription() {
		return "The preferred, concise way to run a Script, a text file of SQL statements and BroadSQL commands. `@foo.bsql` runs a Script from the "
				+ "Scripts Library, `@./foo.bsql` runs a file relative to the working directory (or to the running Script's own directory when "
				+ "written inside a Script), and `@C:\\temp\\foo.bsql` runs a file anywhere. No extension is ever added. Arguments are named, "
				+ "`name=value`, and read in the Script as `${name}`.";
	}

	@Override
	public String getArguments() {
		return "<script> (mandatory) a Scripts Library path, ./relative path, or absolute path, then optional name=value arguments";
	}

	@Override
	public String getExamples() {
		return "@maintenance/cleanup.bsql\n@./helper.bsql\n@reports/customer.bsql customer_id=42 country='FR'\n@\"C:\\My Scripts\\foo.bsql\" since=${last_run}";
	}
}
