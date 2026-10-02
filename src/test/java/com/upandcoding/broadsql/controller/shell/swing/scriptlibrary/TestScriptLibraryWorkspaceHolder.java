package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Only the no-op path is headlessly testable here - actually creating the workspace constructs a real
 * {@link javax.swing.JFrame}, which this dev/test environment cannot do (see the plan's "Headless vs.
 * real GUI verification"). This still matters: most BroadSQL sessions never open the Script Library at
 * all, so {@code BroadSQL.main}'s unconditional shutdown call must be a safe no-op in that common case.
 */
class TestScriptLibraryWorkspaceHolder {

	@Test
	void shutdownIsANoOpWhenTheWorkspaceWasNeverOpened() {
		Assertions.assertNull(ScriptLibraryWorkspaceHolder.frameIfCreated(), "precondition: nothing else in this JVM's test run may have created it");
		Assertions.assertDoesNotThrow(ScriptLibraryWorkspaceHolder::shutdown);
	}
}
