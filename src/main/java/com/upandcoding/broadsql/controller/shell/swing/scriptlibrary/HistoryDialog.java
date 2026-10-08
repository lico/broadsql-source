package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.Revision;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.RevisionSummary;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptAsset;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryService;

/**
 * {@code History} (SPRINT 0917-01, spec section 9): a non-modal window (Compare, once built in Phase E,
 * must be usable while this stays open - spec section 10.3's navigation and the plan's own "non-modal
 * JDialog" requirement) listing every revision of one asset, newest first, with the current revision
 * marked and a read-only preview of whichever revision is selected. Never modifies the active file
 * itself - only {@link #restoreSelected()}, after explicit confirmation, does, via
 * {@link ScriptLibraryService#restoreRevision}.
 */
public final class HistoryDialog extends JDialog {

	/** Notified after a successful Restore so the caller (the open editor tab, if any) can refresh. */
	public interface RestoreListener {
		void onRestored(ScriptAsset restoredAsset);
	}

	/** The Editor's one date/time format: revision times here, and the Metadata tab's Last modified. */
	static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.systemDefault());

	private final ScriptLibraryService service;
	private final String assetId;
	private final String displayName;
	private RestoreListener restoreListener;

	private DefaultTableModel tableModel;
	private JTable table;
	private RSyntaxTextArea previewArea;
	private List<RevisionSummary> revisions;
	private int currentRevisionNumber;

	public HistoryDialog(JFrame owner, ScriptLibraryService service, String assetId, String displayName) {
		super(owner, "History: " + displayName, false);
		this.service = service;
		this.assetId = assetId;
		this.displayName = displayName;
		buildUi();
		reload();
	}

	public void setRestoreListener(RestoreListener restoreListener) {
		this.restoreListener = restoreListener;
	}

	private void buildUi() {
		setLayout(new BorderLayout());

		tableModel = new DefaultTableModel(new Object[] { "Revision", "Date/Time", "Comment", "Current" }, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};
		table = new JTable(tableModel);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				showSelectedPreview();
			}
		});

		previewArea = new RSyntaxTextArea();
		previewArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
		previewArea.setEditable(false);
		RTextScrollPane previewScroll = new RTextScrollPane(previewArea);
		previewScroll.setLineNumbersEnabled(true);

		JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), previewScroll);
		split.setResizeWeight(0.4);
		split.setPreferredSize(new Dimension(700, 500));
		add(split, BorderLayout.CENTER);

		JPanel buttons = new JPanel();
		JButton compareCurrentButton = new JButton("Compare with Current");
		compareCurrentButton.addActionListener(e -> compareSelected(true));
		JButton comparePreviousButton = new JButton("Compare with Previous");
		comparePreviousButton.addActionListener(e -> compareSelected(false));
		JButton restoreButton = new JButton("Restore Selected...");
		restoreButton.addActionListener(e -> restoreSelected());
		JButton closeButton = new JButton("Close");
		closeButton.addActionListener(e -> setVisible(false));
		buttons.add(compareCurrentButton);
		buttons.add(comparePreviousButton);
		buttons.add(restoreButton);
		buttons.add(closeButton);
		add(buttons, BorderLayout.SOUTH);

		setSize(760, 560);
	}

	/** Reloads the revision list from the vault - safe to call again after a Restore (or externally, if the caller wants to refresh). */
	public void reload() {
		try {
			revisions = service.revisionVault().listRevisions(assetId);
			RevisionSummary latest = service.revisionVault().getLatestRevisionSummary(assetId);
			currentRevisionNumber = latest != null ? latest.revisionNumber() : -1;
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
			revisions = List.of();
			currentRevisionNumber = -1;
		}
		tableModel.setRowCount(0);
		// Newest first, per spec section 9's own example.
		for (int i = revisions.size() - 1; i >= 0; i--) {
			RevisionSummary revision = revisions.get(i);
			tableModel.addRow(new Object[] {
					"v" + revision.revisionNumber(),
					TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(revision.timestampMillis())),
					revision.comment() == null ? "" : revision.comment(),
					revision.revisionNumber() == currentRevisionNumber ? "current" : "" });
		}
		if (table.getRowCount() > 0) {
			table.setRowSelectionInterval(0, 0);
		}
	}

	private void showSelectedPreview() {
		RevisionSummary selected = selectedRevision();
		if (selected == null) {
			previewArea.setText("");
			return;
		}
		try {
			Revision full = service.revisionVault().getRevision(assetId, selected.revisionNumber());
			previewArea.setText(full.content());
			previewArea.setCaretPosition(0);
		} catch (BroadSQLException ex) {
			previewArea.setText("ERROR: " + ex.getLocalizedMessage());
		}
	}

	/**
	 * {@code Compare} (spec section 10) - the two mandatory minimum comparisons: selected-vs-current
	 * ({@code compareToCurrent=true}) and selected-vs-previous ({@code compareToCurrent=false}). Opens a
	 * new, non-modal {@link CompareDialog} so this window stays open and usable while comparing.
	 */
	private void compareSelected(boolean compareToCurrent) {
		RevisionSummary selected = selectedRevision();
		if (selected == null) {
			return;
		}
		int otherRevisionNumber = compareToCurrent ? currentRevisionNumber : selected.revisionNumber() - 1;
		if (otherRevisionNumber < 1) {
			JOptionPane.showMessageDialog(this, "v" + selected.revisionNumber() + " has no previous revision to compare with.");
			return;
		}
		try {
			Revision selectedFull = service.revisionVault().getRevision(assetId, selected.revisionNumber());
			Revision otherFull = service.revisionVault().getRevision(assetId, otherRevisionNumber);
			boolean otherIsNewer = otherRevisionNumber > selected.revisionNumber();
			Revision oldRevision = otherIsNewer ? selectedFull : otherFull;
			Revision newRevision = otherIsNewer ? otherFull : selectedFull;

			CompareDialog dialog = new CompareDialog((Frame) getOwner(), "Compare: " + displayName,
					"v" + oldRevision.summary().revisionNumber(), "v" + newRevision.summary().revisionNumber());
			dialog.showComparison(oldRevision.content(), newRevision.content(),
					com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptMetadataHeader.parse(oldRevision.content()),
					com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptMetadataHeader.parse(newRevision.content()),
					oldRevision.summary().relativePathAtRevision(), newRevision.summary().relativePathAtRevision());
			dialog.setLocationRelativeTo(this);
			dialog.setVisible(true);
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private RevisionSummary selectedRevision() {
		int row = table.getSelectedRow();
		if (row < 0 || revisions == null) {
			return null;
		}
		// Table is newest-first; revisions is oldest-first (see reload()).
		return revisions.get(revisions.size() - 1 - row);
	}

	/** {@code Restore} (spec section 11): always creates a new revision, never rewrites/truncates history. */
	private void restoreSelected() {
		RevisionSummary selected = selectedRevision();
		if (selected == null) {
			return;
		}
		int confirm = JOptionPane.showConfirmDialog(this,
				"Restore v" + selected.revisionNumber() + " (" + TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(selected.timestampMillis())) + ")?\n\n"
						+ "This creates a new revision from v" + selected.revisionNumber() + "'s content. "
						+ "Later revisions (v" + (selected.revisionNumber() + 1) + " through v" + currentRevisionNumber + ") remain intact.",
				"Restore", JOptionPane.YES_NO_OPTION);
		if (confirm != JOptionPane.YES_OPTION) {
			return;
		}
		try {
			ScriptAsset restored = service.restoreRevision(assetId, selected.revisionNumber(), null);
			reload();
			if (restoreListener != null) {
				restoreListener.onRestored(restored);
			}
			JOptionPane.showMessageDialog(this, "'" + displayName + "' restored from v" + selected.revisionNumber() + ".");
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: could not restore: " + ex.getLocalizedMessage());
		}
	}
}
