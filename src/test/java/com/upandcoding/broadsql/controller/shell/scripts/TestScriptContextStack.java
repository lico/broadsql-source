package com.upandcoding.broadsql.controller.shell.scripts;

import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;

/** SPRINT 1909S: {@link ScriptContextStack}: frames, cycle detection, depth limit, restoration. */
class TestScriptContextStack {

	private static Path p(String name) {
		return Path.of("/scripts").resolve(name).toAbsolutePath();
	}

	@Test
	void startsEmptyAndPushPopRestoresTheParentFrame() throws BroadSQLException {
		ScriptContextStack stack = new ScriptContextStack();
		Assertions.assertFalse(stack.isInsideScript());
		Assertions.assertNull(stack.current());

		ScriptContextStack.Frame a = stack.push(p("a.bsql"));
		Assertions.assertEquals(ScriptContextStack.Origin.INTERACTIVE, a.getOrigin());
		Assertions.assertEquals(1, a.getDepth());
		ScriptContextStack.Frame b = stack.push(p("sub/b.bsql"));
		Assertions.assertEquals(ScriptContextStack.Origin.SCRIPT, b.getOrigin());
		Assertions.assertEquals(p("sub/b.bsql").getParent(), stack.current().getParentDir());
		ScriptContextStack.Frame c = stack.push(p("c.bsql"));
		Assertions.assertEquals(3, stack.depth());

		stack.pop(c);
		Assertions.assertSame(b, stack.current());
		stack.pop(b);
		Assertions.assertSame(a, stack.current());
		stack.pop(a);
		Assertions.assertFalse(stack.isInsideScript());
	}

	@Test
	void aScriptAlreadyActiveIsRefusedImmediatelyWithTheChain() throws BroadSQLException {
		ScriptContextStack stack = new ScriptContextStack();
		stack.push(p("a.bsql"));
		BroadSQLException self = Assertions.assertThrows(BroadSQLException.class, () -> stack.push(p("a.bsql")));
		Assertions.assertTrue(self.getMessage().contains("Recursive"), self.getMessage());

		stack.push(p("b.bsql"));
		BroadSQLException indirect = Assertions.assertThrows(BroadSQLException.class, () -> stack.push(p("a.bsql")));
		Assertions.assertTrue(indirect.getMessage().contains(p("a.bsql") + " -> " + p("b.bsql") + " -> " + p("a.bsql")), indirect.getMessage());
		Assertions.assertEquals(2, stack.depth(), "a refused push must not leave a frame behind");
	}

	@Test
	void theSameScriptMayRunAgainOnceItHasFinished() throws BroadSQLException {
		ScriptContextStack stack = new ScriptContextStack();
		ScriptContextStack.Frame first = stack.push(p("a.bsql"));
		stack.pop(first);
		stack.push(p("a.bsql"));
		Assertions.assertEquals(1, stack.depth());
	}

	@Test
	void nestingIsLimitedTo32Frames() throws BroadSQLException {
		ScriptContextStack stack = new ScriptContextStack();
		for (int i = 1; i <= ScriptContextStack.MAX_DEPTH; i++) {
			stack.push(p("s" + i + ".bsql"));
		}
		BroadSQLException e = Assertions.assertThrows(BroadSQLException.class, () -> stack.push(p("s33.bsql")));
		Assertions.assertTrue(e.getMessage().contains("32 levels"), e.getMessage());
		Assertions.assertEquals(32, stack.depth());
	}

	@Test
	void popOfAnOuterFrameAlsoDropsAnythingAboveIt() throws BroadSQLException {
		ScriptContextStack stack = new ScriptContextStack();
		ScriptContextStack.Frame a = stack.push(p("a.bsql"));
		stack.push(p("b.bsql"));
		stack.push(p("c.bsql"));
		stack.pop(a);
		Assertions.assertFalse(stack.isInsideScript());
	}
}
