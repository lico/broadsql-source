package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.CatalogFormat;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryService;
import com.upandcoding.broadsql.controller.shell.scripts.ScriptsLibrary;

/**
 * {@code Recently Deleted} (SPRINT 0917-01, spec section 30; reshaped by SPRINT 1909S): the Scripts
 * Library's own archive, newest first, with one {@code Restore} action. There is a single deletion model
 * ({@link ScriptsLibrary#archive}, also behind {@code LIB DEL}), so this dialog is only a presentation of
 * {@link ScriptsLibrary#getArchivedEntries()} and restores through {@link ScriptsLibrary#restoreEntry}
 * (the same operation as {@code LIB RESTORE}/{@code LIB UNDO}); it keeps no deleted-object state of its
 * own. Restoring to a path that is occupied again is refused rather than overwriting the live Script.
 */
public final class RecentlyDeletedDialog extends javax.swing.JDialog {

	private final ScriptLibraryService service;
	private DefaultListModel<ScriptsLibrary.ArchivedEntry> model;
	private JList<ScriptsLibrary.ArchivedEntry> list;
	private Runnable restoredListener;

	public RecentlyDeletedDialog(JFrame owner, ScriptLibraryService service) {
		super(owner, "Recently Deleted", true);
		this.service = service;
		buildUi();
		reload();
	}

	/** Invoked after a successful restore, so the caller can refresh the library tree. */
	public void setRestoredListener(Runnable restoredListener) {
		this.restoredListener = restoredListener;
	}

	private void buildUi() {
		setLayout(new BorderLayout());
		model = new DefaultListModel<>();
		list = new JList<>(model);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer((jList, value, index, isSelected, cellHasFocus) -> {
			javax.swing.JLabel label = new javax.swing.JLabel(
					value.getOriginalRelativePath() + "  (deleted " + CatalogFormat.formatArchiveTimestamp(value.getTimestamp()) + ")");
			label.setOpaque(true);
			if (isSelected) {
				label.setBackground(jList.getSelectionBackground());
				label.setForeground(jList.getSelectionForeground());
			}
			return label;
		});
		JScrollPane scroll = new JScrollPane(list);
		scroll.setPreferredSize(new Dimension(560, 320));
		add(scroll, BorderLayout.CENTER);

		JPanel buttons = new JPanel();
		JButton restoreButton = new JButton("Restore");
		restoreButton.addActionListener(e -> restoreSelected());
		JButton closeButton = new JButton("Close");
		closeButton.addActionListener(e -> setVisible(false));
		buttons.add(restoreButton);
		buttons.add(closeButton);
		add(buttons, BorderLayout.SOUTH);

		setSize(600, 400);
	}

	private void reload() {
		model.clear();
		try {
			for (ScriptsLibrary.ArchivedEntry entry : service.listArchived()) {
				model.addElement(entry);
			}
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void restoreSelected() {
		ScriptsLibrary.ArchivedEntry selected = list.getSelectedValue();
		if (selected == null) {
			return;
		}
		try {
			service.restoreArchived(selected);
			JOptionPane.showMessageDialog(this, "Restored as '" + selected.getOriginalRelativePath() + "'.");
			reload();
			if (restoredListener != null) {
				restoredListener.run();
			}
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}
}
