package com.upandcoding.broadsql.controller.shell.reader;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Answers {@code readLine}/{@code readPassword} from a fixed list, in order: drives the interactive wizards
 * ({@code ADD}/{@code EDIT}/{@code DUPLICATE CONNECTION}) through {@code ShellConsole.inputField} exactly as a
 * user typing would. Fails loudly when the wizard asks more than was scripted.
 */
public final class ScriptedLineReader implements ConsoleLineReader {

	private final Deque<String> answers;

	public ScriptedLineReader(List<String> answers) {
		this.answers = new ArrayDeque<>(answers);
	}

	private String next(String prompt) {
		if (answers.isEmpty()) {
			throw new IllegalStateException("the wizard asked more than was scripted, at: " + prompt);
		}
		return answers.pop();
	}

	@Override
	public String readLine(String prompt) {
		return next(prompt);
	}

	@Override
	public char[] readPassword(String prompt) {
		return next(prompt).toCharArray();
	}

	public int remaining() {
		return answers.size();
	}
}
