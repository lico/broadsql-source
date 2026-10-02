package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JTextPane;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptDiffService;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptMetadataHeader;

/** {@link javax.swing.JPanel}-based, headlessly constructible - same precedent as {@code TestScriptLibraryBrowserPanel}/{@code TestMetadataPanel}. {@link CompareDialog} itself ({@code JDialog}-based) is out of headless reach, same limitation as {@code ScriptLibraryFrame}. */
class TestComparePanel {

	private final ScriptDiffService diffService = new ScriptDiffService();

	@Test
	void showDiffPopulatesBothTextPanesWithTheExpectedContent() {
		ComparePanel panel = new ComparePanel("Old", "New");

		panel.showDiff(diffService.diffContent("select 1;", "select 2;"),
				diffService.diffMetadata(ScriptMetadataHeader.parse("select 1;"), ScriptMetadataHeader.parse("select 2;")),
				diffService.diffPath("q.sql", "q.sql"));

		List<JTextPane> panes = findTextPanes(panel);
		Assertions.assertEquals(2, panes.size());
		Assertions.assertTrue(panes.get(0).getText().contains("select 1;"));
		Assertions.assertTrue(panes.get(1).getText().contains("select 2;"));
	}

	@Test
	void summaryLabelReflectsTheChangeCounts() {
		ComparePanel panel = new ComparePanel("Old", "New");

		panel.showDiff(diffService.diffContent("a\nb", "a\nb\nc"),
				diffService.diffMetadata(ScriptMetadataHeader.parse("a"), ScriptMetadataHeader.parse("a")),
				diffService.diffPath("q.sql", "q.sql"));

		JLabel summary = findFirstLabel(panel);
		Assertions.assertNotNull(summary);
		Assertions.assertTrue(summary.getText().contains("1 addition"), summary.getText());
	}

	@Test
	void showDiffCanBeCalledRepeatedlyToShowADifferentComparison() {
		ComparePanel panel = new ComparePanel("Old", "New");

		panel.showDiff(diffService.diffContent("a", "b"),
				diffService.diffMetadata(ScriptMetadataHeader.parse("a"), ScriptMetadataHeader.parse("a")),
				diffService.diffPath("x.sql", "x.sql"));
		panel.showDiff(diffService.diffContent("select 1;", "select 1;"),
				diffService.diffMetadata(ScriptMetadataHeader.parse("select 1;"), ScriptMetadataHeader.parse("select 1;")),
				diffService.diffPath("y.sql", "y.sql"));

		List<JTextPane> panes = findTextPanes(panel);
		Assertions.assertTrue(panes.get(0).getText().contains("select 1;"));
	}

	@Test
	void navigationMethodsNeverThrowEvenWithNoChangesYet() {
		ComparePanel panel = new ComparePanel("Old", "New");

		Assertions.assertDoesNotThrow(panel::nextChange);
		Assertions.assertDoesNotThrow(panel::previousChange);
	}

	@Test
	void navigationMethodsNeverThrowAfterShowingARealDiff() {
		ComparePanel panel = new ComparePanel("Old", "New");
		panel.showDiff(diffService.diffContent("a\nb\nc", "a\nX\nc"),
				diffService.diffMetadata(ScriptMetadataHeader.parse("a"), ScriptMetadataHeader.parse("a")),
				diffService.diffPath("q.sql", "q.sql"));

		Assertions.assertDoesNotThrow(panel::nextChange);
		Assertions.assertDoesNotThrow(panel::nextChange);
		Assertions.assertDoesNotThrow(panel::previousChange);
	}

	private static List<JTextPane> findTextPanes(Container container) {
		List<JTextPane> found = new ArrayList<>();
		collect(container, JTextPane.class, found);
		return found;
	}

	private static JLabel findFirstLabel(Container container) {
		List<JLabel> found = new ArrayList<>();
		collect(container, JLabel.class, found);
		return found.isEmpty() ? null : found.get(0);
	}

	@SuppressWarnings("unchecked")
	private static <T extends Component> void collect(Container container, Class<T> type, List<T> out) {
		for (Component c : container.getComponents()) {
			if (type.isInstance(c)) {
				out.add((T) c);
			}
			if (c instanceof Container inner) {
				collect(inner, type, out);
			}
		}
	}
}
