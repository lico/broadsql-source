package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;

import com.upandcoding.broadsql.controller.shell.scriptlibrary.MetadataDiffResult;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.PathDiffResult;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptDiffService;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptMetadataHeader;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.TextDiffResult;

/**
 * {@code Compare} (SPRINT 0917-01, spec section 10) - a non-modal window (so it can stay open
 * alongside {@link HistoryDialog}, per spec section 10.3's navigation expectations and the plan's own
 * "Compare-from-History must stay open while comparing" requirement) hosting one {@link ComparePanel}
 * plus Previous/Next Change buttons. Strictly read-only (spec section 10.7) - not a merge editor, no
 * write path exists anywhere in this class or {@link ComparePanel}.
 */
public final class CompareDialog extends JDialog {

	private final ComparePanel comparePanel;

	public CompareDialog(Frame owner, String title, String oldLabel, String newLabel) {
		super(owner, title, false);
		comparePanel = new ComparePanel(oldLabel, newLabel);
		buildUi();
	}

	private void buildUi() {
		setLayout(new BorderLayout());
		add(comparePanel, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton previousButton = new JButton("Previous Change");
		previousButton.addActionListener(e -> comparePanel.previousChange());
		JButton nextButton = new JButton("Next Change");
		nextButton.addActionListener(e -> comparePanel.nextChange());
		JButton closeButton = new JButton("Close");
		closeButton.addActionListener(e -> setVisible(false));
		buttons.add(previousButton);
		buttons.add(nextButton);
		buttons.add(closeButton);
		add(buttons, BorderLayout.SOUTH);

		setSize(1000, 650);
	}

	/**
	 * Computes and shows the comparison, using a fresh {@link ScriptDiffService} internally (it is
	 * stateless, so a shared instance is unnecessary here).
	 */
	public void showComparison(String oldContent, String newContent, ScriptMetadataHeader oldHeader, ScriptMetadataHeader newHeader,
			String oldPath, String newPath) {
		ScriptDiffService diffService = new ScriptDiffService();
		TextDiffResult textDiff = diffService.diffContent(oldContent, newContent);
		MetadataDiffResult metadataDiff = diffService.diffMetadata(oldHeader, newHeader);
		PathDiffResult pathDiff = diffService.diffPath(oldPath, newPath);
		comparePanel.showDiff(textDiff, metadataDiff, pathDiff);
	}
}
