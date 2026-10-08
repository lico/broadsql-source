package com.upandcoding.broadsql.controller.shell.repeat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;
import com.upandcoding.broadsql.controller.shell.commands.core.sql.CommandDefault;
import com.upandcoding.broadsql.dao.LastQueryResult;
import com.upandcoding.broadsql.dao.LastQueryResultHolder;

/**
 * GitHub #196: installed on the {@code CommandInterpreter} while a {@code REPEAT} runs, and called by
 * {@code executeCommand()} around every statement it dispatches, however deeply nested (a block statement, the
 * statements of a repeated {@code @script} or {@code LIB RUN} script, the scripts those call):
 * <ul>
 * <li>before the statement executes: {@link RepeatSafety} must accept it, otherwise the statement fails with the
 * reason and nothing is sent to the database;</li>
 * <li>after it succeeded: a SQL query's tabular result, the snapshot the screen display just recorded
 * ({@link LastQueryResultHolder}), is collected for the monitoring output, in execution order. The display itself is
 * unchanged.</li>
 * </ul>
 * Its presence is also what tells the CTRL+C handler that a {@code REPEAT} is running, including while it waits
 * between two iterations, when no command thread runs.
 */
public final class RepeatGuard {

	/** A tabular result of the current iteration: the statement that produced it, and its displayed snapshot. */
	public record CapturedResult(String statement, LastQueryResult result) {
	}

	private LastQueryResult snapshotBefore;
	private final List<CapturedResult> results = new ArrayList<>();

	/** @throws BroadSQLException when {@code statement} may not run inside a {@code REPEAT} */
	public void beforeStatement(Command command, String statement) throws BroadSQLException {
		String problem = RepeatSafety.problem(command, statement);
		if (problem != null) {
			throw new BroadSQLException(problem);
		}
		snapshotBefore = LastQueryResultHolder.get();
	}

	/** {@code statement} completed without throwing; collects the tabular result a SQL query displayed, if any. */
	public void afterStatement(Command command, String statement) {
		if (command != null && !(command instanceof CommandDefault)) {
			return;
		}
		LastQueryResult now = LastQueryResultHolder.get();
		if (now != null && now != snapshotBefore) {
			results.add(new CapturedResult(statement, now));
		}
	}

	/** Forgets the previous iteration's results. */
	public void beginIteration() {
		results.clear();
	}

	/** The tabular results of the current iteration, in execution order. */
	public List<CapturedResult> results() {
		return Collections.unmodifiableList(results);
	}
}
