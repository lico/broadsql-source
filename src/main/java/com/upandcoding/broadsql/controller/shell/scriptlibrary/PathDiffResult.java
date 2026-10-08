package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.Objects;

/** The result of {@link ScriptDiffService#diffPath} - spec section 10.6: "if the asset name/path changed between revisions, show it explicitly." */
public final class PathDiffResult {

	private final String oldPath;
	private final String newPath;

	public PathDiffResult(String oldPath, String newPath) {
		this.oldPath = oldPath;
		this.newPath = newPath;
	}

	public String oldPath() {
		return oldPath;
	}

	public String newPath() {
		return newPath;
	}

	public boolean changed() {
		return !Objects.equals(oldPath, newPath);
	}
}
