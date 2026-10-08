package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.ArrayList;
import java.util.List;

/**
 * A headless {@link ScriptLibraryLauncher} test double that only records what was requested, never
 * constructing a real {@link javax.swing.JFrame} - lets the editor command tests ({@code EDIT},
 * {@code LIB EDIT}) verify the open/focus decision without touching Swing at all. See
 * {@link ScriptLibraryLauncher}'s own javadoc.
 */
public final class FakeScriptLibraryLauncher implements ScriptLibraryLauncher {

	public final List<String> calls = new ArrayList<>();
	public String openedRelativePath;
	public String offeredCreateName;
	public ScriptRunContext lastContext;

	@Override
	public void openWorkspace(ScriptRunContext context) {
		calls.add("openWorkspace");
		this.lastContext = context;
	}

	@Override
	public void openAsset(String relativePath, ScriptRunContext context) {
		calls.add("openAsset");
		this.openedRelativePath = relativePath;
		this.lastContext = context;
	}

	@Override
	public void offerCreate(String requestedName, ScriptRunContext context) {
		calls.add("offerCreate");
		this.offeredCreateName = requestedName;
		this.lastContext = context;
	}
}
